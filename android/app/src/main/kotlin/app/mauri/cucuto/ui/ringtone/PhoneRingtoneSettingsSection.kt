package app.mauri.cucuto.ui.ringtone

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.R
import app.mauri.cucuto.core.ringtone.PhoneRingtones
import app.mauri.cucuto.ui.settings.SettingsSection
import app.mauri.cucuto.ui.settings.SettingsSwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Settings switch for phone ringtones (#52). */
@HiltViewModel
class PhoneRingtoneSettingsViewModel
    @Inject
    constructor(
        private val ringtones: PhoneRingtones,
    ) : ViewModel() {
        val enabled: StateFlow<Boolean> =
            ringtones.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** After the contacts permission was granted. */
        fun enable() {
            viewModelScope.launch { runCatching { ringtones.enable() } }
        }

        /** Restores every contact's own ringtone and deletes Cucuto's ring files. */
        fun disable() {
            viewModelScope.launch { runCatching { ringtones.disable() } }
        }
    }

/**
 * "Phone ringtones" in Settings: one switch. Turning it on explains and asks for the contacts permission; turning it
 * off asks first, because it gives every contact its old ringtone back.
 */
@Composable
fun PhoneRingtoneSettingsSection(
    modifier: Modifier = Modifier,
    viewModel: PhoneRingtoneSettingsViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    val turnOn = rememberContactsPermissionFlow(onGrant = viewModel::enable)
    SettingsSection(R.string.settings_section_ringtones, modifier) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_ringtones_switch),
            supporting =
                stringResource(
                    if (enabled) R.string.settings_ringtones_on else R.string.settings_ringtones_off,
                ),
            checked = enabled,
            onCheckedChange = { on -> if (on) turnOn() else confirmOff = true },
        )
        Text(
            text = stringResource(R.string.settings_ringtones_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.settings_ringtones_off_title)) },
            text = { Text(stringResource(R.string.settings_ringtones_off_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOff = false
                        viewModel.disable()
                    },
                ) { Text(stringResource(R.string.settings_ringtones_off_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.ringtone_not_now)) }
            },
        )
    }
}
