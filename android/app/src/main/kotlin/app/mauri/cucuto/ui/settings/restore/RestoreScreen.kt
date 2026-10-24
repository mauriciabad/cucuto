package app.mauri.cucuto.ui.settings.restore

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.backup.PackFailure
import app.mauri.cucuto.core.backup.PackRestoreAction
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.ringtone.ContactLinkRestore
import app.mauri.cucuto.core.ringtone.UnmatchedContact
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.ui.components.BottomActionBar
import app.mauri.cucuto.ui.components.PushedScreen
import app.mauri.cucuto.ui.components.isLargeFont
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.ringtone.rememberContactsPermissionFlow
import app.mauri.cucuto.ui.ringtone.rememberReadContactsPermissionFlow
import app.mauri.cucuto.ui.settings.ZIP_TYPES
import app.mauri.cucuto.ui.settings.launchSafely
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * Restore from a backup (docs/project-brief.md, "Restore on a new phone"; ADR 0028, ADR 0055): a screen pushed on the
 * tab it was started from (Settings, or Home's first-run card) for the document at [backupUri]. Back leaves it at any
 * step; nothing already restored is undone. A problem can pick another file, which replaces this screen
 * ([onPickAnother]); the summary's links to the setup guide and Diagnostics replace it too ([onLeave]), so back from
 * them returns where the restore started.
 */
@Composable
fun RestoreScreen(
    backupUri: String,
    onBack: () -> Unit,
    onPickAnother: (backupUri: String) -> Unit,
    onLeave: (CucutoDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RestoreViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel, backupUri) { viewModel.start(backupUri) }
    val step by viewModel.step.collectAsStateWithLifecycle()
    var pickingFor by rememberSaveable { mutableStateOf<String?>(null) }
    val pickPack =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val id = pickingFor
            pickingFor = null
            if (uri != null && id != null) viewModel.importPackFile(id, uri.toString())
        }
    val pickBackup =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onPickAnother(uri.toString())
        }
    val feedback = LocalFeedback.current
    val turnOnRingtones = rememberContactsPermissionFlow(onGrant = viewModel::enablePhoneRingtones)
    val findContacts = rememberReadContactsPermissionFlow(onGrant = viewModel::findContacts)
    var linkingContact by rememberSaveable { mutableStateOf<Long?>(null) }
    val pickContact =
        rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            val id = linkingContact
            linkingContact = null
            if (uri != null && id != null) viewModel.linkContact(id, uri.toString())
        }
    LaunchedEffect(step is RestoreStep.Done) {
        if (step is RestoreStep.Done) feedback.fire(Feedback.Success)
    }

    RestoreContent(
        step = step,
        actions =
            RestoreActions(
                onClose = onBack,
                onPassphrase = viewModel::submitPassphrase,
                onPickAnother = { launchSafely { pickBackup.launch(ZIP_TYPES) } },
                onRetryPack = viewModel::restorePack,
                onPickPack = { id ->
                    pickingFor = id
                    launchSafely { pickPack.launch(ZIP_TYPES) }
                },
                onContinue = viewModel::continueToContacts,
                onToggleConflict = viewModel::toggleConflict,
                onSelectAll = viewModel::selectAllConflicts,
                onApplyConflicts = viewModel::applyConflicts,
                onOpenSetup = { onLeave(CucutoDestination.Onboarding) },
                onOpenDiagnostics = { onLeave(CucutoDestination.Diagnostics) },
                onKeepForBackups = viewModel::keepForBackups,
                onTurnOnPhoneRingtones = turnOnRingtones,
                onFindContacts = findContacts,
                onPickContact = { id ->
                    linkingContact = id
                    launchSafely { pickContact.launch(null) }
                },
            ),
        modifier = modifier,
    )
}

internal data class RestoreActions(
    /** Leaves the restore: back, a problem's Close, the summary's Finish. */
    val onClose: () -> Unit = {},
    val onPassphrase: (CharArray) -> Unit = {},
    val onPickAnother: () -> Unit = {},
    val onRetryPack: (String) -> Unit = {},
    val onPickPack: (String) -> Unit = {},
    val onContinue: () -> Unit = {},
    val onToggleConflict: (String) -> Unit = {},
    val onSelectAll: (Boolean) -> Unit = {},
    val onApplyConflicts: () -> Unit = {},
    val onOpenSetup: () -> Unit = {},
    val onOpenDiagnostics: () -> Unit = {},
    /** Turns automatic backups on into the restored file. */
    val onKeepForBackups: () -> Unit = {},
    /** Asks for the contacts permission (explained first), then turns phone ringtones on. */
    val onTurnOnPhoneRingtones: () -> Unit = {},
    /** Asks for the permission to read contacts (explained first), then finds the backup's linked contacts here. */
    val onFindContacts: () -> Unit = {},
    /** Opens Android's contact picker for an unmatched contact (its Cucuto contact ID). */
    val onPickContact: (Long) -> Unit = {},
)

@Composable
internal fun RestoreContent(
    step: RestoreStep,
    actions: RestoreActions,
    modifier: Modifier = Modifier,
) {
    PushedScreen(
        title = stringResource(R.string.restore_title),
        onBack = actions.onClose,
        modifier = modifier,
    ) { padding ->
        Box(Modifier.fillMaxSize().consumeWindowInsets(padding).padding(padding)) {
            when (step) {
                // Opening starts right away; there is nothing else to show meanwhile.
                RestoreStep.Closed -> WorkingStep(RestoreStep.Phase.OPENING)

                is RestoreStep.Working -> WorkingStep(step.phase)

                is RestoreStep.Passphrase -> PassphraseStep(step, actions.onPassphrase)

                is RestoreStep.Problem -> ProblemStep(step, actions)

                is RestoreStep.Packs -> PacksStep(step, actions)

                is RestoreStep.Conflicts -> ConflictsStep(step, actions)

                is RestoreStep.Done -> DoneStep(step, actions)
            }
        }
    }
}

@Composable
private fun WorkingStep(phase: RestoreStep.Phase) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The text below says what is happening; the indicator itself is decorative.
        LoadingIndicator(Modifier.clearAndSetSemantics {})
        Text(
            stringResource(
                if (phase ==
                    RestoreStep.Phase.OPENING
                ) {
                    R.string.restore_opening
                } else {
                    R.string.restore_restoring
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PassphraseStep(
    step: RestoreStep.Passphrase,
    onSubmit: (CharArray) -> Unit,
) {
    // Not saveable: a password does not belong in saved state.
    var passphrase by remember { mutableStateOf("") }
    val submit = {
        if (passphrase.isNotEmpty()) {
            onSubmit(passphrase.toCharArray())
            passphrase = ""
        }
    }
    StepColumn {
        StepTitle(R.string.restore_passphrase_title)
        Text(stringResource(R.string.restore_passphrase_body))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text(stringResource(R.string.export_passphrase)) },
            isError = step.wrong,
            supportingText = if (step.wrong) ({ Text(stringResource(R.string.restore_passphrase_wrong)) }) else null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = submit, enabled = passphrase.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.restore_passphrase_open))
        }
    }
}

@Composable
private fun ProblemStep(
    step: RestoreStep.Problem,
    actions: RestoreActions,
) {
    val (title, body) =
        when (step.kind) {
            ProblemKind.PACK_NOT_BACKUP -> R.string.restore_problem_pack_title to R.string.restore_problem_pack_body
            ProblemKind.INVALID -> R.string.restore_problem_invalid_title to R.string.restore_problem_invalid_body
            ProblemKind.FAILED -> R.string.restore_problem_failed_title to R.string.restore_problem_failed_body
        }
    StepColumn {
        Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        StepTitle(title)
        Text(stringResource(body))
        if (step.details.isNotEmpty()) {
            Text(stringResource(R.string.restore_problem_details), style = MaterialTheme.typography.titleSmall)
            step.details.take(MAX_DETAILS).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = actions.onPickAnother, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.restore_pick_other))
            }
            TextButton(onClick = actions.onClose, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.restore_close))
            }
        }
    }
}

@Composable
private fun PacksStep(
    step: RestoreStep.Packs,
    actions: RestoreActions,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepTitle(R.string.restore_packs_title)
                    Text(stringResource(R.string.restore_packs_body, step.createdAt.take(DATE_CHARS)))
                }
            }
            items(step.rows, key = { it.id }) { row -> PackRowItem(row, actions) }
        }
        BottomActionBar {
            val missing = step.missing
            Button(onClick = actions.onContinue, enabled = !step.busy, shapes = ButtonDefaults.shapes()) {
                Text(
                    if (missing == 0) {
                        stringResource(R.string.restore_packs_continue)
                    } else {
                        pluralStringResource(R.plurals.restore_packs_continue_missing, missing, missing)
                    },
                )
            }
        }
    }
}

@Composable
private fun PackRowItem(
    row: PackRow,
    actions: RestoreActions,
) {
    val status = row.status
    val button: (@Composable () -> Unit)? =
        when {
            status is PackStatus.Done -> {
                null
            }

            row.item.action == PackRestoreAction.PICK_FILE && status !is PackStatus.Working -> {
                {
                    FilledTonalButton(onClick = { actions.onPickPack(row.id) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.restore_pack_pick_button))
                    }
                }
            }

            status is PackStatus.Failed -> {
                {
                    OutlinedButton(onClick = { actions.onRetryPack(row.id) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.restore_pack_retry))
                    }
                }
            }

            else -> {
                null
            }
        }
    // With large fonts the button goes under the text, which would otherwise be squeezed a word per line (#226).
    val buttonBelow = isLargeFont()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            ListItem(
                headlineContent = { Text(row.name) },
                supportingContent = { Text(packStatusText(row), color = packStatusColor(status)) },
                trailingContent = {
                    if (status is PackStatus.Done) {
                        // The status line already says "Installed" in words.
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else if (!buttonBelow) {
                        button?.invoke()
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
            if (buttonBelow && button != null) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { button() }
            }
            if (status is PackStatus.Working) {
                val fraction = status.fraction
                val bar = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                if (fraction ==
                    null
                ) {
                    LinearWavyProgressIndicator(bar)
                } else {
                    LinearWavyProgressIndicator(progress = { fraction }, modifier = bar)
                }
            }
        }
    }
}

@Composable
private fun packStatusText(row: PackRow): String =
    when (val status = row.status) {
        PackStatus.Done -> {
            stringResource(R.string.restore_pack_installed)
        }

        is PackStatus.Working -> {
            stringResource(R.string.restore_pack_working)
        }

        is PackStatus.Failed -> {
            stringResource(
                when (status.reason) {
                    PackFailure.DOWNLOAD -> R.string.restore_pack_failed_download
                    PackFailure.NOT_IN_REPOSITORY -> R.string.restore_pack_failed_missing
                    PackFailure.HASH_MISMATCH -> R.string.restore_pack_failed_hash
                    PackFailure.WRONG_FILE -> R.string.restore_pack_failed_wrong_file
                    PackFailure.INVALID -> R.string.restore_pack_failed_invalid
                    PackFailure.OTHER -> R.string.restore_pack_failed_other
                },
            )
        }

        PackStatus.Pending -> {
            when (row.item.action) {
                PackRestoreAction.INSTALLED -> {
                    stringResource(R.string.restore_pack_installed)
                }

                PackRestoreAction.FROM_BACKUP -> {
                    stringResource(R.string.restore_pack_from_backup)
                }

                PackRestoreAction.DOWNLOAD_FROM_REPOSITORY -> {
                    stringResource(R.string.restore_pack_from_repository)
                }

                PackRestoreAction.DOWNLOAD_FROM_URL -> {
                    stringResource(R.string.restore_pack_from_url)
                }

                PackRestoreAction.PICK_FILE -> {
                    row.item.reference.source.fileName
                        ?.let { stringResource(R.string.restore_pack_pick, it) }
                        ?: stringResource(R.string.restore_pack_pick_unknown)
                }
            }
        }
    }

@Composable
private fun packStatusColor(status: PackStatus) =
    if (status is PackStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun ConflictsStep(
    step: RestoreStep.Conflicts,
    actions: RestoreActions,
) {
    val feedback = LocalFeedback.current
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepTitle(R.string.restore_conflicts_title)
                    Text(pluralStringResource(R.plurals.restore_conflicts_body, step.rows.size, step.rows.size))
                    // 48 dp tall: the checkbox alone is 24 dp, too small to tap next to the first row (#226).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                                step.allSelected,
                                role = Role.Checkbox,
                            ) {
                                feedback.fire(if (it) Feedback.ToggleOn else Feedback.ToggleOff)
                                actions.onSelectAll(it)
                            },
                    ) {
                        Checkbox(checked = step.allSelected, onCheckedChange = null)
                        Text(stringResource(R.string.restore_conflicts_select_all), Modifier.padding(start = 12.dp))
                    }
                }
            }
            items(step.rows, key = { it.backupId }) { row ->
                val selected = row.backupId in step.selected
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    ListItem(
                        leadingContent = { Checkbox(checked = selected, onCheckedChange = null) },
                        headlineContent = { Text(row.name ?: stringResource(R.string.restore_conflict_unnamed)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.restore_conflict_here, voiceText(row.current)))
                                Text(stringResource(R.string.restore_conflict_backup, voiceText(row.backup)))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier =
                            Modifier.toggleable(selected, role = Role.Checkbox) {
                                feedback.fire(if (it) Feedback.ToggleOn else Feedback.ToggleOff)
                                actions.onToggleConflict(row.backupId)
                            },
                    )
                }
            }
        }
        BottomActionBar {
            if (step.selected.isEmpty()) {
                Button(onClick = actions.onApplyConflicts, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.restore_conflicts_keep))
                }
            } else {
                Button(onClick = actions.onApplyConflicts, shapes = ButtonDefaults.shapes()) {
                    Text(
                        pluralStringResource(R.plurals.restore_conflicts_apply, step.selected.size, step.selected.size),
                    )
                }
            }
        }
    }
}

@Composable
private fun voiceText(label: VoiceLabel): String {
    val voice =
        if (label.character == null) {
            stringResource(R.string.restore_conflict_no_voice)
        } else {
            stringResource(R.string.restore_conflict_voice, label.character, label.pack.orEmpty())
        }
    val policy =
        when (label.groupPolicy) {
            GroupPolicy.PER_SENDER -> stringResource(R.string.restore_conflict_per_sender)
            GroupPolicy.GROUP -> stringResource(R.string.restore_conflict_group)
            null -> return voice
        }
    return "$voice · $policy"
}

@Composable
private fun DoneStep(
    step: RestoreStep.Done,
    actions: RestoreActions,
) {
    val summary = step.summary
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    StepTitle(R.string.restore_done_title)
                    SummaryLine(R.plurals.restore_done_contacts, summary.contacts, summary.created)
                    SummaryLine(R.plurals.restore_done_voices, summary.voicesApplied)
                    if (summary.conflictsKept > 0) SummaryLine(R.plurals.restore_done_kept, summary.conflictsKept)
                    if (summary.keysLeftAlone > 0) SummaryLine(R.plurals.restore_done_keys, summary.keysLeftAlone)
                    if (summary.repositoriesAdded >
                        0
                    ) {
                        SummaryLine(R.plurals.restore_done_repositories, summary.repositoriesAdded)
                    }
                    if (summary.credentialsRestored >
                        0
                    ) {
                        SummaryLine(R.plurals.restore_done_credentials, summary.credentialsRestored)
                    }
                    if (summary.packsMissing >
                        0
                    ) {
                        SummaryLine(R.plurals.restore_done_packs_missing, summary.packsMissing)
                    }
                    if (summary.ringtonesKept > 0) {
                        SummaryLine(R.plurals.restore_done_ringtones_kept, summary.ringtonesKept)
                    }
                }
            }
            summary.contactLinks?.takeIf { it.restored > 0 }?.let { links ->
                item { ContactsCard(links, actions.onFindContacts, actions.onPickContact) }
                if (links.ringtones > 0) {
                    item { PhoneRingtonesCard(links, summary.phoneRingtonesWaiting, actions.onTurnOnPhoneRingtones) }
                }
            }
            step.reusableSource?.let { source ->
                item { KeepForBackupsCard(kept = step.keptForBackups, onKeep = actions.onKeepForBackups) }
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.restore_checklist_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(stringResource(R.string.restore_checklist_body))
                        listOf(
                            R.string.restore_checklist_enable,
                            R.string.restore_checklist_scope,
                            R.string.restore_checklist_reboot,
                            R.string.restore_checklist_test,
                        ).forEachIndexed { index, line ->
                            Text("${index + 1}. ${stringResource(line)}")
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(onClick = actions.onOpenSetup, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(R.string.restore_open_setup))
                            }
                            TextButton(onClick = actions.onOpenDiagnostics, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(R.string.settings_section_diagnostics))
                            }
                        }
                    }
                }
            }
        }
        BottomActionBar {
            Button(
                onClick = actions.onClose,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.restore_finish)) }
        }
    }
}

/**
 * The backup's linked contacts on this phone (ADR 0028's amendment for #43): lookup keys are per device, so they are
 * found again here. Without the permission to read contacts, why it is needed and "Find contacts"; then how many were
 * found, and a row with "Choose contact" (Android's contact picker) for each one that was not.
 */
@Composable
private fun ContactsCard(
    links: ContactLinkRestore,
    onFind: () -> Unit,
    onPick: (Long) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.restore_contacts_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (links.waitingForPermission) {
                Text(pluralStringResource(R.plurals.restore_contacts_waiting, links.restored, links.restored))
                FilledTonalButton(onClick = onFind, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.restore_contacts_find))
                }
                return@Column
            }
            if (links.linked > 0) {
                Text(
                    pluralStringResource(R.plurals.restore_contacts_found, links.linked, links.linked),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (links.unmatched.isNotEmpty()) {
                Text(
                    pluralStringResource(
                        R.plurals.restore_contacts_unmatched,
                        links.unmatched.size,
                        links.unmatched.size,
                    ),
                )
                links.unmatched.forEach { contact -> UnmatchedRow(contact, onPick) }
            }
        }
    }
}

@Composable
private fun UnmatchedRow(
    contact: UnmatchedContact,
    onPick: (Long) -> Unit,
) {
    val name = contact.displayName ?: stringResource(R.string.chat_unnamed)
    val chooseFor = stringResource(R.string.restore_contacts_choose_for, name)
    // Stacked rather than a list item with a trailing button: at large font sizes the name keeps the full width.
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall)
        if (contact.ringtone) {
            Text(
                stringResource(R.string.restore_contacts_ringtone_waits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = { onPick(contact.contactId) },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.semantics { contentDescription = chooseFor },
        ) { Text(stringResource(R.string.ringtone_pick_contact)) }
    }
}

/**
 * The restored phone ringtones (ADR 0057): how many came back, and the one tap that turns them on when they wait for
 * it. The contacts they could not find are listed in [ContactsCard].
 */
@Composable
private fun PhoneRingtonesCard(
    links: ContactLinkRestore,
    waiting: Boolean,
    onTurnOn: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.restore_ringtones_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(pluralStringResource(R.plurals.restore_ringtones_back, links.ringtones, links.ringtones))
            if (waiting) {
                Text(stringResource(R.string.restore_ringtones_off))
                FilledTonalButton(onClick = onTurnOn, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.ringtone_turn_on))
                }
            } else if (links.ringtonesLinked > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.restore_ringtones_written,
                        links.ringtonesLinked,
                        links.ringtonesLinked,
                    ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** After restoring from a plain backup file: keep backing up into it, so a phone in Drive carries on (ADR 0040). */
@Composable
private fun KeepForBackupsCard(
    kept: Boolean,
    onKeep: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.restore_keep_file_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (kept) {
                Text(stringResource(R.string.restore_keep_file_done))
            } else {
                Text(stringResource(R.string.restore_keep_file_body))
                FilledTonalButton(onClick = onKeep, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.restore_keep_file_action))
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(
    plural: Int,
    count: Int,
    vararg more: Int,
) {
    Text(pluralStringResource(plural, count, count, *more.toTypedArray()), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun StepColumn(content: @Composable () -> Unit) {
    // Scrolls: with large fonts a problem's details are taller than the screen.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun StepTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
}

private const val MAX_DETAILS = 8

/** `2026-10-24T09:30:00Z` → `2026-10-24`. */
private const val DATE_CHARS = 10

@Preview
@Composable
private fun ConflictsPreview() {
    CucutoTheme(dynamicColor = false) {
        RestoreContent(
            step =
                RestoreStep.Conflicts(
                    rows =
                        listOf(
                            ConflictRow(
                                "1",
                                "Ada",
                                VoiceLabel("Frog", "Critters", null),
                                VoiceLabel("Owl", "Critters", null),
                            ),
                            ConflictRow("2", null, VoiceLabel(null, null, null), VoiceLabel("Kettle", "Kitchen", null)),
                        ),
                    selected = setOf("1"),
                ),
            actions = RestoreActions(),
        )
    }
}
