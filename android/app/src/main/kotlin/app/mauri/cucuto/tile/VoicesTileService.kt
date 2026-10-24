package app.mauri.cucuto.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.settings.RoutingSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The "Cucuto voices" Quick Settings tile (#28): the global switch, one tap from anywhere. It writes the same setting
 * as Settings, so the routing table is republished the same way (RoutingPublisher follows the setting).
 */
@AndroidEntryPoint
class VoicesTileService : TileService() {
    @Inject lateinit var routing: RoutingSettings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch { routing.enabled.collect(::render) }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val enabled = !routing.enabled.first()
            routing.setEnabled(enabled)
            render(enabled)
        }
    }

    private fun render(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_voices)
        tile.subtitle = getString(if (enabled) R.string.tile_voices_on else R.string.tile_voices_off)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
