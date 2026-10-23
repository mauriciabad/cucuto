package app.mauri.cucuto.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * libxposed API 102 entry point, listed in `META-INF/xposed/java_init.list` and scoped to the system framework
 * (`system_server`) only.
 *
 * This runs inside `system_server`, so every callback is defensive: nothing may ever escape, because an exception
 * here could break notifications for the whole phone. On any failure WhatsApp simply keeps its default tone.
 * For now the module only logs that it loaded; the notification hooks arrive with the M0 spike.
 */
class CucutoModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        guarded("onModuleLoaded") {
            log(
                Log.INFO,
                TAG,
                "Loaded in ${param.processName} by $frameworkName ($frameworkVersionCode), API $apiVersion",
            )
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        guarded("onSystemServerStarting") {
            log(Log.INFO, TAG, "system_server is starting; no hooks installed yet")
        }
    }

    private inline fun guarded(
        stage: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            // Last line of defense: report and carry on. Never rethrow inside system_server.
            try {
                Log.e(TAG, "$stage failed; WhatsApp keeps its default tone", t)
            } catch (_: Throwable) {
                // Logging itself failed; there is nothing safe left to do.
            }
        }
    }

    internal companion object {
        const val TAG = "cucuto"
    }
}
