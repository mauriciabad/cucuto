package app.mauri.cucuto.core.backup

import app.mauri.cucuto.core.data.settings.RoutingSettings
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The `settings` object of a backup. Keys are owned by the app; each one is listed here explicitly, so device-specific
 * state (the automatic backup folder, permissions, caches) never travels to another phone.
 */
interface SettingsBackup {
    suspend fun export(): JsonObject

    /** Applies the known keys of [settings] and reports which keys were used and which were ignored. */
    suspend fun restore(settings: JsonObject): SettingsRestoreResult
}

data class SettingsRestoreResult(
    val applied: List<String>,
    val ignored: List<String>,
)

/** The app's settings in backups. Add a key here when a new setting should survive a phone change. */
class AppSettingsBackup(
    private val routing: RoutingSettings,
) : SettingsBackup {
    override suspend fun export(): JsonObject = JsonObject(mapOf(ENABLED to JsonPrimitive(routing.enabled.first())))

    override suspend fun restore(settings: JsonObject): SettingsRestoreResult {
        val applied = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        for ((key, value) in settings) {
            when (key) {
                ENABLED -> {
                    val enabled = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    if (enabled == null) {
                        ignored += key
                    } else {
                        routing.setEnabled(enabled)
                        applied += key
                    }
                }

                else -> {
                    ignored += key
                }
            }
        }
        return SettingsRestoreResult(applied, ignored)
    }

    companion object {
        /** The global switch ([RoutingSettings.enabled]). */
        const val ENABLED = "enabled"
    }
}
