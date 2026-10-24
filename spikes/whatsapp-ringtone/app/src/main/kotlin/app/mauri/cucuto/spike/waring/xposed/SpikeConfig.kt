package app.mauri.cucuto.spike.waring.xposed

import android.content.SharedPreferences

/**
 * The spike's settings, written by the app ([app.mauri.cucuto.spike.waring.app.ConfigReceiver]) to libxposed Remote
 * Preferences and read by the module inside WhatsApp's process. The production feature would carry the same facts
 * in Cucuto's routing table (`RoutingContract`) instead.
 *
 * @property mode [Mode.OFF] (default: the module installs no hook at all), [Mode.OBSERVE] or [Mode.SWAP].
 * @property testName The display name of the one test contact ("Cucuto Test", as in the M9 run). Only calls whose
 *   incoming-call notification shows exactly this name are swapped. Never logged.
 * @property ringUri The file to ring with in swap mode, normally `content://media/external/audio/media/<id>` in
 *   `Ringtones/cucuto/`.
 */
data class SpikeConfig(
    val mode: Mode = Mode.OFF,
    val testName: String? = null,
    val ringUri: String? = null,
) {
    enum class Mode { OFF, OBSERVE, SWAP }

    companion object {
        const val GROUP = "waring"
        const val KEY_MODE = "mode"
        const val KEY_TEST_NAME = "test_name"
        const val KEY_RING_URI = "ring_uri"

        /** Reads the settings; anything missing or malformed means [Mode.OFF]. Never throws. */
        fun read(preferences: SharedPreferences?): SpikeConfig =
            try {
                if (preferences == null) {
                    SpikeConfig()
                } else {
                    SpikeConfig(
                        mode = parseMode(preferences.getString(KEY_MODE, null)),
                        testName = preferences.getString(KEY_TEST_NAME, null)?.trim()?.takeIf { it.isNotEmpty() },
                        ringUri = preferences.getString(KEY_RING_URI, null)?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }
            } catch (_: Throwable) {
                SpikeConfig()
            }

        fun parseMode(value: String?): Mode =
            when (value?.trim()?.lowercase()) {
                "observe" -> Mode.OBSERVE
                "swap" -> Mode.SWAP
                else -> Mode.OFF
            }
    }
}
