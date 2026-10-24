package app.mauri.cucuto.ui.settings.restore

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.ui.settings.ZIP_TYPES
import app.mauri.cucuto.ui.settings.launchSafely

/**
 * How a screen starts a restore (ADR 0028): Settings → Backup and restore, and Home's first-run card, where someone
 * moving to a new phone looks first (ADR 0050). The returned function opens the system file picker; the picked
 * backup opens the Restore screen ([CucutoDestination.Restore], ADR 0055) on the current tab.
 */
@Composable
fun rememberBackupPicker(onNavigate: (CucutoDestination) -> Unit): () -> Unit {
    val navigate by rememberUpdatedState(onNavigate)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) navigate(CucutoDestination.Restore(uri.toString()))
        }
    return remember(launcher) { { launchSafely { launcher.launch(ZIP_TYPES) } } }
}
