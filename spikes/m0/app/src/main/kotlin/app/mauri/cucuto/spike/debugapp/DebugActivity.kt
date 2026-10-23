package app.mauri.cucuto.spike.debugapp

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mauri.cucuto.spike.xposed.SpikeHaptics

/**
 * Debug screen for the M0 spike. Everything here is also reachable from adb through
 * [SpikeReceiver]; the buttons exist so the owner can run the session without a laptop in hand.
 */
class DebugActivity : Activity() {

    private lateinit var readout: TextView
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationFactory.ensureChannel(this)
        requestPostNotificationsIfNeeded()

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        readout = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(readout)

        button(root, "Post 1:1 message") { NotificationFactory.postOneToOne(this) }
        button(root, "Post 1:1 (@lid identity)") { NotificationFactory.postOneToOne(this, lid = true) }
        button(root, "Post 1:1 with setOnlyAlertOnce") { NotificationFactory.postOneToOne(this, onlyAlertOnce = true) }
        button(root, "Post group (alert: children)") { NotificationFactory.postGroupConversation(this) }
        button(root, "Post group (alert: summary)") {
            NotificationFactory.postGroupConversation(this, Notification.GROUP_ALERT_SUMMARY)
        }
        button(root, "Quick burst (5)") { NotificationFactory.postBurst(this) }
        button(root, "Play clip locally (provider check)") { playClipLocally() }
        button(root, "Refresh readout") { refresh() }
        button(root, "Cancel all") { NotificationFactory.cancelAll(this) }

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        refresh()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun refresh() {
        val nm = getSystemService(NotificationManager::class.java)
        val am = getSystemService(AudioManager::class.java)
        val ringer = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
            else -> "unknown(${am.ringerMode})"
        }
        val dnd = when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL (off)"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE"
            else -> "unknown(${nm.currentInterruptionFilter})"
        }

        readout.text = buildString {
            appendLine("cucuto M0 spike")
            appendLine()
            appendLine("Clip URI: ${NotificationFactory.clipUri()}")
            appendLine()
            appendLine("VibrationEffect.Builder present: ${SpikeHaptics.builderAvailable}")
            appendLine("BasicEnvelopeBuilder present:    ${SpikeHaptics.basicEnvelopeBuilderAvailable}")
            SpikeHaptics.build()
            appendLine("Haptic strategy chosen: ${SpikeHaptics.lastStrategy}")
            appendLine()
            appendLine("Ringer mode: $ringer")
            appendLine("DND filter:  $dnd")
            appendLine("Notifications permitted: ${nm.areNotificationsEnabled()}")
            appendLine()
            appendLine("Watch the hook with:")
            appendLine("  adb logcat -s cucuto-m0")
        }
    }

    /**
     * Reads the clip through our own provider. If this plays but the notification is silent, the
     * problem is the *caller's* access, not the clip — which is exactly the distinction issue #11
     * needs to make.
     */
    private fun playClipLocally() {
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@DebugActivity, NotificationFactory.clipUri())
                prepare()
                start()
            }
        }.onFailure {
            readout.text = "Local clip playback failed: ${it.javaClass.simpleName}: ${it.message}\n\n${readout.text}"
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private inline fun button(parent: LinearLayout, label: String, crossinline onClick: () -> Unit) {
        parent.addView(
            Button(this).apply {
                text = label
                setOnClickListener {
                    runCatching { onClick() }.onFailure {
                        readout.text = "Action failed: ${it.javaClass.simpleName}: ${it.message}\n\n${readout.text}"
                    }
                }
            },
        )
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
