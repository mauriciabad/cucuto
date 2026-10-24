package app.mauri.cucuto.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.R
import app.mauri.cucuto.ui.motion.SharedBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Whether setup needs the user right now (#180, ADR 0050), for the shell: the Settings tab's badge and the banner on
 * top of Settings. It lives as long as the activity, so switching tabs never re-probes; the shell calls [refresh] when
 * the app comes back to the foreground (scope, LSPosed and installed apps change in other apps).
 *
 * Calm on purpose: only checks the user must fix count ([needsAttention]). Missing packs (Home's journey offers them),
 * waiting for the self-test after a reboot, a scope LSPosed does not report, and an update that only waits for the next
 * reboot while voices keep working never light the badge.
 */
@HiltViewModel
class SetupAttentionViewModel
    @Inject
    constructor(
        reports: StatusReports,
    ) : ViewModel() {
        private val refreshes = MutableStateFlow(0)

        val needsAttention: StateFlow<Boolean> =
            reports
                .reports(refreshes)
                .map { it.needsAttention }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        fun refresh() {
            refreshes.value++
        }

        private companion object {
            /** Survives a configuration change without re-probing. */
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/**
 * One line saying setup needs the user, opening Settings → Setup: on Home once setup has been green, and on top of
 * Settings. Its bounds are shared with the Setup screen's status card.
 */
@Composable
fun SetupAttentionBanner(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SharedBounds(SetupCardKey, modifier) {
        Card(
            onClick = onOpen,
            shape = MaterialTheme.shapes.extraLarge,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(Icons.Rounded.Warning, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_attention_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_attention_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
