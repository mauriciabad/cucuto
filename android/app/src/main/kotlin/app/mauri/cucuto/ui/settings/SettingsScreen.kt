package app.mauri.cucuto.ui.settings

import android.content.ActivityNotFoundException
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.BuildConfig
import app.mauri.cucuto.R
import app.mauri.cucuto.backup.AutoBackupResult
import app.mauri.cucuto.core.backup.BackupFileNames
import app.mauri.cucuto.core.data.settings.AutoBackupState
import app.mauri.cucuto.core.data.settings.UiSoundLevel
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.ringtone.PhoneRingtoneSettingsSection
import app.mauri.cucuto.ui.settings.restore.RestoreActions
import app.mauri.cucuto.ui.settings.restore.RestoreFlow
import app.mauri.cucuto.ui.settings.restore.RestoreViewModel
import app.mauri.cucuto.ui.theme.CucutoTheme
import app.mauri.cucuto.ui.theme.rememberReducedMotion

/**
 * Settings (#28, ADR 0028): the global switch, sound and feel, backup and restore, setup, diagnostics, about and
 * (debug builds) developer tools. Restore runs as a full-screen flow on top ([RestoreFlow]).
 */
@Composable
fun SettingsScreen(
    onNavigate: (CucutoDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    restoreViewModel: RestoreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var licenses by rememberSaveable { mutableStateOf(false) }

    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFileNames.MIME_TYPE)) { uri ->
            viewModel.exportTo(uri?.toString())
        }
    val chooseFolder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            viewModel.chooseFolder(uri?.toString())
        }
    val pickBackup =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { restoreViewModel.open(it.toString()) }
        }

    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        snackbar.showSnackbar(messageText(resources, shown))
        viewModel.messageShown()
    }

    SettingsContent(
        state = state,
        systemRemovesAnimations = rememberReducedMotion(),
        snackbar = snackbar,
        phoneRingtones = { PhoneRingtoneSettingsSection() },
        actions =
            SettingsActions(
                onEnabled = viewModel::setEnabled,
                onSoundLevel = viewModel::setSoundLevel,
                onHaptics = viewModel::setHaptics,
                onRemoveAnimations = viewModel::setRemoveAnimations,
                onExport = { exportDialog = true },
                onRestore = { launchSafely { pickBackup.launch(ZIP_TYPES) } },
                onChooseFolder = { launchSafely { chooseFolder.launch(null) } },
                onTurnOffAutoBackup = viewModel::turnOffAutoBackup,
                onAutoIncludePacks = viewModel::setAutoIncludePacks,
                onBackUpNow = viewModel::backUpNow,
                onLicenses = { licenses = true },
                onNavigate = onNavigate,
            ),
        modifier = modifier,
    )

    if (exportDialog) {
        ExportDialog(
            onDismiss = { exportDialog = false },
            onConfirm = { form ->
                exportDialog = false
                if (viewModel.prepareExport(form)) launchSafely { createDocument.launch(viewModel.suggestedFileName()) }
            },
        )
    }
    if (licenses) LicensesDialog(onDismiss = { licenses = false })

    val restoreStep by restoreViewModel.step.collectAsStateWithLifecycle()
    RestoreFlow(
        step = restoreStep,
        actions =
            RestoreActions(
                onClose = restoreViewModel::close,
                onPassphrase = restoreViewModel::submitPassphrase,
                onPickAnother = {
                    restoreViewModel.close()
                    launchSafely { pickBackup.launch(ZIP_TYPES) }
                },
                onRetryPack = restoreViewModel::restorePack,
                onContinue = restoreViewModel::continueToContacts,
                onToggleConflict = restoreViewModel::toggleConflict,
                onSelectAll = restoreViewModel::selectAllConflicts,
                onApplyConflicts = restoreViewModel::applyConflicts,
                onOpenSetup = {
                    restoreViewModel.close()
                    onNavigate(CucutoDestination.Onboarding)
                },
                onOpenDiagnostics = {
                    restoreViewModel.close()
                    onNavigate(CucutoDestination.Diagnostics)
                },
            ),
        onPackFilePick = restoreViewModel::importPackFile,
    )
}

/** Backups and packs are plain zips; some providers report them as octet streams. */
internal val ZIP_TYPES = arrayOf(BackupFileNames.MIME_TYPE, "application/x-zip-compressed", "application/octet-stream")

/** A device without a document picker (rare, but it exists): do nothing instead of crashing. */
internal inline fun launchSafely(block: () -> Unit) {
    try {
        block()
    } catch (e: ActivityNotFoundException) {
        // Nothing to pick with; the row stays as it was.
    }
}

/** Everything the content can ask for. */
internal class SettingsActions(
    val onEnabled: (Boolean) -> Unit = {},
    val onSoundLevel: (UiSoundLevel) -> Unit = {},
    val onHaptics: (Boolean) -> Unit = {},
    val onRemoveAnimations: (Boolean) -> Unit = {},
    val onExport: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onChooseFolder: () -> Unit = {},
    val onTurnOffAutoBackup: () -> Unit = {},
    val onAutoIncludePacks: (Boolean) -> Unit = {},
    val onBackUpNow: () -> Unit = {},
    val onLicenses: () -> Unit = {},
    val onNavigate: (CucutoDestination) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsState,
    systemRemovesAnimations: Boolean,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    phoneRingtones: (@Composable () -> Unit)? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "voices") { VoicesSection(state.enabled, actions.onEnabled) }
            if (phoneRingtones != null) item(key = "ringtones") { phoneRingtones() }
            item(key = "feel") { FeelSection(state, systemRemovesAnimations, actions) }
            item(key = "language") { LanguageSection() }
            item(key = "backup") { BackupSection(state, actions) }
            item(key = "setup") { SetupSection(actions.onNavigate) }
            item(key = "diagnostics") { DiagnosticsSection(actions.onNavigate) }
            item(key = "about") { AboutSection(actions.onLicenses) }
            if (BuildConfig.DEBUG) item(key = "developer") { DeveloperSection(actions.onNavigate) }
        }
    }
}

@Composable
private fun VoicesSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    SettingsSection(R.string.settings_section_voices) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_voices_switch),
            supporting = stringResource(if (enabled) R.string.settings_voices_on else R.string.settings_voices_off),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        Text(
            text = stringResource(R.string.settings_voices_tile_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun FeelSection(
    state: SettingsState,
    systemRemovesAnimations: Boolean,
    actions: SettingsActions,
) {
    val feedback = LocalFeedback.current
    SettingsSection(R.string.settings_section_feel) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_ui_sounds)) },
            supportingContent = { Text(stringResource(R.string.settings_ui_sounds_body)) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
        val levels = UiSoundLevel.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            levels.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = state.soundLevel == level,
                    onClick = {
                        actions.onSoundLevel(level)
                        feedback.fire(Feedback.SegmentTick)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, levels.size),
                ) { Text(stringResource(level.label())) }
            }
        }
        SettingsSwitchRow(
            title = stringResource(R.string.settings_ui_haptics),
            supporting = stringResource(R.string.settings_ui_haptics_body),
            checked = state.haptics,
            onCheckedChange = actions.onHaptics,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_remove_animations),
            supporting =
                stringResource(
                    if (systemRemovesAnimations) {
                        R.string.settings_remove_animations_system
                    } else {
                        R.string.settings_remove_animations_body
                    },
                ),
            checked = state.removeAnimations || systemRemovesAnimations,
            enabled = !systemRemovesAnimations,
            onCheckedChange = actions.onRemoveAnimations,
        )
    }
}

private fun UiSoundLevel.label(): Int =
    when (this) {
        UiSoundLevel.Off -> R.string.settings_ui_sounds_off
        UiSoundLevel.Quiet -> R.string.settings_ui_sounds_quiet
        UiSoundLevel.Normal -> R.string.settings_ui_sounds_normal
    }

@Composable
private fun BackupSection(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSection(R.string.settings_section_backup) {
        SettingsLinkRow(
            title = stringResource(R.string.settings_backup_now),
            supporting = stringResource(R.string.settings_backup_now_body),
            onClick = actions.onExport,
        )
        SettingsLinkRow(
            title = stringResource(R.string.settings_restore),
            supporting = stringResource(R.string.settings_restore_body),
            onClick = actions.onRestore,
        )
        AutoBackupRows(state.autoBackup, state.backupBusy, actions)
    }
}

@Composable
private fun AutoBackupRows(
    auto: AutoBackupState,
    busy: Boolean,
    actions: SettingsActions,
) {
    Column {
        val folder = auto.folderName ?: stringResource(R.string.settings_auto_backup_folder_unknown)
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_auto_backup)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (auto.enabled) {
                            stringResource(R.string.settings_auto_backup_on, folder)
                        } else {
                            stringResource(R.string.settings_auto_backup_off)
                        },
                    )
                    if (auto.enabled) Text(lastBackupText(auto), color = lastBackupColor(auto))
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
        if (busy) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = actions.onChooseFolder, shapes = ButtonDefaults.shapes()) {
                val change = R.string.settings_auto_backup_change
                Text(stringResource(if (auto.enabled) change else R.string.settings_auto_backup_choose))
            }
            if (auto.enabled) {
                OutlinedButton(onClick = actions.onBackUpNow, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.settings_auto_backup_run))
                }
                TextButton(onClick = actions.onTurnOffAutoBackup, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.settings_auto_backup_turn_off))
                }
            }
        }
        if (auto.enabled) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_backup_packs),
                supporting = stringResource(R.string.settings_auto_backup_packs_body),
                checked = auto.includePacks,
                onCheckedChange = actions.onAutoIncludePacks,
            )
        }
    }
}

@Composable
private fun lastBackupText(auto: AutoBackupState): String {
    val context = LocalContext.current
    val error = auto.lastError
    val success = auto.lastSuccessAt
    return when {
        error != null && (success == null || (auto.lastErrorAt ?: 0) > success) -> {
            stringResource(R.string.settings_auto_backup_error, error)
        }

        success != null -> {
            val time =
                DateUtils.formatDateTime(context, success, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
            stringResource(R.string.settings_auto_backup_last, time)
        }

        else -> {
            stringResource(R.string.settings_auto_backup_never)
        }
    }
}

@Composable
private fun lastBackupColor(auto: AutoBackupState) =
    if (auto.lastError != null && (auto.lastSuccessAt == null || (auto.lastErrorAt ?: 0) > auto.lastSuccessAt!!)) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun SetupSection(onNavigate: (CucutoDestination) -> Unit) {
    SettingsSection(R.string.settings_section_setup) {
        SettingsLinkRow(
            title = stringResource(R.string.settings_setup_status),
            supporting = stringResource(R.string.settings_setup_status_body),
            onClick = { onNavigate(CucutoDestination.Status) },
        )
        SettingsLinkRow(
            title = stringResource(R.string.settings_setup_guide),
            supporting = stringResource(R.string.settings_setup_guide_body),
            onClick = { onNavigate(CucutoDestination.Onboarding) },
        )
    }
}

@Composable
private fun DiagnosticsSection(onNavigate: (CucutoDestination) -> Unit) {
    SettingsSection(R.string.settings_section_diagnostics) {
        SettingsLinkRow(
            title = stringResource(R.string.settings_diagnostics),
            supporting = stringResource(R.string.settings_diagnostics_body),
            onClick = { onNavigate(CucutoDestination.Diagnostics) },
        )
    }
}

@Composable
private fun AboutSection(onLicenses: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val website = stringResource(R.string.settings_website_url)
    val source = stringResource(R.string.settings_source_url)
    SettingsSection(R.string.settings_section_about) {
        SettingsInfoRow(
            title = stringResource(R.string.settings_version),
            value = stringResource(R.string.settings_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        )
        SettingsLinkRow(
            title = stringResource(R.string.settings_website),
            supporting = website.removePrefix("https://"),
            onClick = { openLink(uriHandler, website) },
        )
        SettingsLinkRow(
            title = stringResource(R.string.settings_source),
            supporting = source.removePrefix("https://"),
            onClick = { openLink(uriHandler, source) },
        )
        SettingsLinkRow(
            title = stringResource(R.string.settings_licenses),
            supporting = stringResource(R.string.settings_licenses_body),
            onClick = onLicenses,
        )
    }
}

internal fun openLink(
    uriHandler: androidx.compose.ui.platform.UriHandler,
    url: String,
) {
    try {
        uriHandler.openUri(url)
    } catch (e: IllegalArgumentException) {
        // No browser: nothing sensible to do from here.
    } catch (e: ActivityNotFoundException) {
        // Same.
    }
}

@Composable
private fun DeveloperSection(onNavigate: (CucutoDestination) -> Unit) {
    SettingsSection(R.string.settings_section_developer) {
        SettingsLinkRow(
            title = stringResource(R.string.settings_gallery),
            supporting = stringResource(R.string.settings_gallery_body),
            onClick = { onNavigate(CucutoDestination.Gallery) },
        )
        SettingsInfoRow(
            title = stringResource(R.string.settings_build),
            value = stringResource(R.string.settings_build_value, BuildConfig.APPLICATION_ID, BuildConfig.BUILD_TYPE),
        )
    }
}

private fun messageText(
    resources: android.content.res.Resources,
    message: BackupMessage,
): String =
    when (message) {
        is BackupMessage.Exported -> {
            if (message.outcome.encrypted) {
                resources.getString(R.string.backup_exported_encrypted)
            } else {
                resources.getQuantityString(
                    R.plurals.backup_exported,
                    message.outcome.contacts,
                    message.outcome.contacts,
                )
            }
        }

        is BackupMessage.ExportFailed -> {
            resources.getString(R.string.backup_export_failed, message.message)
        }

        BackupMessage.FolderFailed -> {
            resources.getString(R.string.backup_folder_failed)
        }

        is BackupMessage.AutoResult -> {
            when (val result = message.result) {
                AutoBackupResult.Off -> resources.getString(R.string.backup_auto_off)
                AutoBackupResult.Unchanged -> resources.getString(R.string.backup_auto_unchanged)
                is AutoBackupResult.Written -> resources.getString(R.string.backup_auto_written, result.fileName)
                is AutoBackupResult.Failed -> resources.getString(R.string.backup_auto_failed, result.message)
            }
        }
    }

@Preview
@Composable
private fun SettingsPreview() {
    CucutoTheme(dynamicColor = false) {
        SettingsContent(
            state =
                SettingsState(
                    autoBackup =
                        AutoBackupState(
                            folderUri = "content://tree",
                            folderName = "Backups",
                            lastSuccessAt = 1_761_000_000_000,
                        ),
                ),
            systemRemovesAnimations = false,
            actions = SettingsActions(),
        )
    }
}
