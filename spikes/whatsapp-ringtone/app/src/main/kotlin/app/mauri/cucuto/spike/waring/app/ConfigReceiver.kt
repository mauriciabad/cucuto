package app.mauri.cucuto.spike.waring.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.cucuto.spike.waring.xposed.SpikeConfig
import io.github.libxposed.service.XposedService

/**
 * adb-driven settings for the device session (README.md). Only the shell can send it (`android.permission.DUMP`).
 *
 * ```
 * adb shell am broadcast -n app.mauri.cucuto.spike.waring/.app.ConfigReceiver \
 *     --es mode observe|swap|off [--es test_name 'Cucuto Test'] [--es ring_uri content://media/external/audio/media/<id>]
 * adb shell am broadcast -n app.mauri.cucuto.spike.waring/.app.ConfigReceiver --es scope show|request|remove
 * ```
 *
 * The module reads the mode when WhatsApp starts: `off` (the default) installs nothing, so going from `off` to
 * `observe` or `swap` needs a WhatsApp restart (force stop). Between `observe` and `swap`, and for the name and URI,
 * the module re-reads the values at each ring, so no restart is needed.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        Thread {
            try {
                val service = SpikeApp.awaitService(seconds = 5)
                if (service == null) {
                    Log.w(SpikeApp.TAG, "config: no framework service (is the module enabled in LSPosed?)")
                    return@Thread
                }
                intent.getStringExtra(EXTRA_SCOPE)?.let { action ->
                    scope(service, action)
                    return@Thread
                }
                val mode = SpikeConfig.parseMode(intent.getStringExtra(SpikeConfig.KEY_MODE))
                val editor = service.getRemotePreferences(SpikeConfig.GROUP).edit()
                editor.putString(SpikeConfig.KEY_MODE, mode.name.lowercase())
                intent.getStringExtra(SpikeConfig.KEY_TEST_NAME)?.let { editor.putString(SpikeConfig.KEY_TEST_NAME, it) }
                intent.getStringExtra(SpikeConfig.KEY_RING_URI)?.let { editor.putString(SpikeConfig.KEY_RING_URI, it) }
                editor.apply()
                // The name is the test contact's, but keep the log free of it anyway.
                Log.i(SpikeApp.TAG, "config: mode=$mode name=${intent.hasExtra(SpikeConfig.KEY_TEST_NAME)}")
            } catch (t: Throwable) {
                Log.w(SpikeApp.TAG, "config: writing Remote Preferences failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * The opt-in mechanism under test (spike report, section 6): `show` logs the scope and the processes the module is
     * loaded in (`getRunningTargets`, API 102), `request` asks the framework to add WhatsApp (LSPosed posts a prompt
     * the user approves), `remove` gives the scope back.
     */
    private fun scope(
        service: XposedService,
        action: String,
    ) {
        when (action) {
            "show" -> {
                Log.i(SpikeApp.TAG, "scope: ${service.scope}")
                val targets =
                    try {
                        service.runningTargets.joinToString { "${it.processName}(${it.state})" }
                    } catch (t: Throwable) {
                        "unavailable: ${t.javaClass.simpleName}"
                    }
                Log.i(SpikeApp.TAG, "running targets: $targets")
            }

            "request" -> {
                service.requestScope(
                    listOf(WHATSAPP),
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) {
                            Log.i(SpikeApp.TAG, "scope request approved: $approved")
                        }

                        override fun onScopeRequestFailed(message: String) {
                            Log.w(SpikeApp.TAG, "scope request failed: $message")
                        }
                    },
                )
                Log.i(SpikeApp.TAG, "scope request sent; approve it from the LSPosed notification")
            }

            "remove" -> {
                service.removeScope(listOf(WHATSAPP))
                Log.i(SpikeApp.TAG, "scope removed: now ${service.scope}")
            }

            else -> {
                Log.w(SpikeApp.TAG, "scope: unknown action (show, request, remove)")
            }
        }
    }

    private companion object {
        const val EXTRA_SCOPE = "scope"
        const val WHATSAPP = "com.whatsapp"
    }
}
