package app.mauri.cucuto.ui.settings.restore

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.ui.settings.ZIP_TYPES
import app.mauri.cucuto.ui.settings.launchSafely

/**
 * A restore (ADR 0028) for any screen that offers one: Settings → Backup and restore, and Home's first-run card, where
 * someone moving to a new phone looks first (ADR 0050). [content] gets `pickBackup`, which opens the system file
 * picker; the picked backup then runs through the full-screen restore flow on top: passphrase, packs, conflicts and
 * the summary, whose links leave through [onNavigate]. [onKeepForBackups] turns automatic backups on into the
 * restored file (ADR 0040) when the user asks for it.
 */
@Composable
fun RestoreHost(
    onNavigate: (CucutoDestination) -> Unit,
    onKeepForBackups: (documentUri: String) -> Unit,
    viewModel: RestoreViewModel = hiltViewModel(),
    content: @Composable (pickBackup: () -> Unit) -> Unit,
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.open(it.toString()) }
        }
    val pickBackup: () -> Unit = remember(launcher) { { launchSafely { launcher.launch(ZIP_TYPES) } } }
    content(pickBackup)

    val step by viewModel.step.collectAsStateWithLifecycle()
    RestoreFlow(
        step = step,
        actions =
            RestoreActions(
                onClose = viewModel::close,
                onPassphrase = viewModel::submitPassphrase,
                onPickAnother = {
                    viewModel.close()
                    pickBackup()
                },
                onRetryPack = viewModel::restorePack,
                onContinue = viewModel::continueToContacts,
                onToggleConflict = viewModel::toggleConflict,
                onSelectAll = viewModel::selectAllConflicts,
                onApplyConflicts = viewModel::applyConflicts,
                onOpenSetup = {
                    viewModel.close()
                    onNavigate(CucutoDestination.Onboarding)
                },
                onOpenDiagnostics = {
                    viewModel.close()
                    onNavigate(CucutoDestination.Diagnostics)
                },
                onKeepForBackups = { uri ->
                    onKeepForBackups(uri)
                    viewModel.keepForBackups()
                },
            ),
        onPackFilePick = viewModel::importPackFile,
    )
}
