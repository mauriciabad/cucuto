package app.mauri.cucuto.packs.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.pack.ImportProgress
import app.mauri.cucuto.core.pack.InstallAction
import app.mauri.cucuto.packs.ImportProblem
import app.mauri.cucuto.packs.ImportUi
import app.mauri.cucuto.packs.ProblemItem
import app.mauri.cucuto.packs.ReviewPack
import app.mauri.cucuto.packs.warningItems
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/**
 * The import, start to end, in one sheet: progress while the file is read and checked, then the review (pick packs of
 * a bundle, see what installing does, meet the characters one by one) or the problem in plain words. The sheet
 * cannot be dismissed while packs are being installed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    import: ImportUi,
    onSelect: (packId: String, selected: Boolean) -> Unit,
    onInstall: () -> Unit,
    onPickAnother: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val installing by rememberUpdatedState((import as? ImportUi.Review)?.installing == true)
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            confirmValueChange = { it != SheetValue.Hidden || !installing },
        )
    ModalBottomSheet(
        onDismissRequest = { if (!installing) onClose() },
        modifier = modifier,
        sheetState = sheetState,
        sheetGesturesEnabled = !installing,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (import) {
                ImportUi.Idle -> Unit
                is ImportUi.Working -> WorkingContent(import.progress, import.download, onCancel = onClose)
                is ImportUi.Review -> ReviewContent(import, onSelect, onInstall, onClose)
                is ImportUi.Problem -> ProblemContent(import.problem, onPickAnother, onClose)
            }
        }
    }
}

@Composable
private fun WorkingContent(
    progress: ImportProgress?,
    download: ImportUi.Download?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadingIndicator(Modifier.size(64.dp))
        Text(
            text =
                if (download != null) {
                    stringResource(R.string.import_step_downloading, download.name)
                } else {
                    stringResource(stepText(progress))
                },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (download != null) DownloadBar(download) else ProgressBar(progress)
        TextButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) }
    }
}

/** The download's progress, eased between the client's progress reports, with "1.2 MB of 4.8 MB" below. */
@Composable
private fun DownloadBar(
    download: ImportUi.Download,
    modifier: Modifier = Modifier,
) {
    val total = download.total?.takeIf { it > 0 }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (total != null) {
            val fraction by animateFloatAsState(
                targetValue = (download.bytes.toFloat() / total).coerceIn(0f, 1f),
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "download",
            )
            LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.import_download_size, sizeText(download.bytes), sizeText(total)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ProgressBar(
    progress: ImportProgress?,
    modifier: Modifier = Modifier,
) {
    val fraction = progress?.fraction()
    if (fraction != null) {
        LinearWavyProgressIndicator(progress = { fraction }, modifier = modifier.fillMaxWidth())
    } else {
        LinearWavyProgressIndicator(modifier = modifier.fillMaxWidth())
    }
}

@Composable
private fun ReviewContent(
    state: ImportUi.Review,
    onSelect: (String, Boolean) -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val review = state.review
    val player = rememberClipPlayer()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = review.bundle?.name ?: review.packs.single().name,
                style = MaterialTheme.typography.headlineSmall,
            )
            val subtitle =
                if (review.isBundle) {
                    pluralStringResource(R.plurals.import_bundle_subtitle, review.packs.size, review.packs.size)
                } else {
                    review.packs.single().candidate.manifest.author.let {
                        stringResource(R.string.packs_by_author, it)
                    }
                }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            review.fileName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        review.packs.forEach { pack ->
            ReviewPackCard(
                pack = pack,
                showCheckbox = review.isBundle,
                selected = state.selection.isSelected(pack.id),
                enabled = !state.installing,
                player = player,
                onSelectedChange = { onSelect(pack.id, it) },
            )
        }

        val warnings = warningItems(review.warnings)
        if (warnings.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                warnings.forEach { ProblemRow(it, Icons.Rounded.Info) }
            }
        }

        if (state.installing) {
            Column(
                Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.import_step_installing),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                ProgressBar(state.progress)
            }
        } else {
            Row(
                Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) }
                Button(onClick = onInstall, enabled = state.canInstall, shapes = ButtonDefaults.shapes()) {
                    Text(installLabel(state))
                }
            }
        }
    }
}

/** One pack of the review: what installing does, and its characters popping in one by one. */
@Composable
private fun ReviewPackCard(
    pack: ReviewPack,
    showCheckbox: Boolean,
    selected: Boolean,
    enabled: Boolean,
    player: ClipPlayer,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    val toggle =
        if (showCheckbox) {
            Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox) {
                feedback.fire(if (it) Feedback.ToggleOn else Feedback.ToggleOff)
                onSelectedChange(it)
            }
        } else {
            Modifier
        }
    Card(
        modifier = modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = toggle.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showCheckbox) Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
                Column(Modifier.weight(1f).padding(start = if (showCheckbox) 0.dp else 4.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        actionText(pack),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (pack.candidate.action.needsConfirmation) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
            RevealedCharacters(pack, player)
            if (pack.orphanedContacts > 0) {
                Text(
                    pluralStringResource(R.plurals.import_orphaned, pack.orphanedContacts, pack.orphanedContacts),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The brief's preview: characters appear one by one (all at once with "Remove animations"); tap one to hear it. */
@Composable
private fun RevealedCharacters(
    pack: ReviewPack,
    player: ClipPlayer,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val motion = MaterialTheme.motionScheme
    var revealed by remember(pack.id) { mutableIntStateOf(if (reduced) pack.characters.size else 0) }
    LaunchedEffect(pack.id, reduced) {
        while (revealed < pack.characters.size) {
            delay(REVEAL_STEP_MS)
            revealed++
        }
    }
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(pack.characters, key = { _, c -> c.id }) { index, character ->
            AnimatedVisibility(
                visible = index < revealed,
                enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            ) {
                CharacterBadge(character, playerKey = "review/${pack.id}/${character.id}", player = player)
            }
        }
    }
}

@Composable
private fun ProblemContent(
    problem: ImportProblem,
    onPickAnother: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    LaunchedEffect(problem) { feedback.fire(Feedback.Error) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = MaterialShapes.Cookie6Sided.toShape(),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Warning, contentDescription = null) }
        }
        Text(
            stringResource(problem.title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Text(stringResource(problem.summary), style = MaterialTheme.typography.bodyLarge)
        problem.items.forEach { ProblemRow(it, Icons.Rounded.Warning) }
        if (problem.hiddenItems > 0) {
            Text(
                pluralStringResource(R.plurals.import_more_problems, problem.hiddenItems, problem.hiddenItems),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (problem.details.isNotEmpty()) {
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(
                    stringResource(if (showDetails) R.string.import_details_hide else R.string.import_details_show),
                )
            }
            if (showDetails) {
                SelectionContainer {
                    Text(
                        problem.details.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (onPickAnother != null) {
                TextButton(onClick = onPickAnother) { Text(stringResource(R.string.import_pick_another)) }
            }
            Button(onClick = onClose, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.import_ok)) }
        }
    }
}

@Composable
private fun ProblemRow(
    item: ProblemItem,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
        Column(Modifier.weight(1f)) {
            Text(stringResource(item.message), style = MaterialTheme.typography.bodyMedium)
            item.file?.let { file ->
                val where =
                    if (item.moreFiles == 0) {
                        file
                    } else {
                        pluralStringResource(R.plurals.import_file_and_more, item.moreFiles, file, item.moreFiles)
                    }
                Text(
                    where,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun stepText(progress: ImportProgress?): Int =
    when (progress) {
        null, is ImportProgress.Copying -> R.string.import_step_reading

        ImportProgress.Validating -> R.string.import_step_checking

        is ImportProgress.Extracting -> R.string.import_step_unpacking

        is ImportProgress.Decoding -> R.string.import_step_testing

        // Auto haptics run once the user confirmed: part of installing for them.
        is ImportProgress.Analyzing, is ImportProgress.Installing -> R.string.import_step_installing
    }

/** How far the current step is, or null when unknown. */
internal fun ImportProgress.fraction(): Float? =
    when (this) {
        is ImportProgress.Copying -> totalBytes?.takeIf { it > 0 }?.let { bytesCopied.toFloat() / it }
        ImportProgress.Validating -> null
        is ImportProgress.Extracting -> ratio(done, total)
        is ImportProgress.Decoding -> ratio(done, total)
        is ImportProgress.Analyzing -> ratio(done, total)
        is ImportProgress.Installing -> ratio(done, total)
    }?.coerceIn(0f, 1f)

private fun ratio(
    done: Int,
    total: Int,
): Float? = if (total > 0) done.toFloat() / total else null

@Composable
private fun actionText(pack: ReviewPack): String {
    val c = pack.candidate
    val characters = pluralStringResource(R.plurals.packs_characters, pack.characters.size, pack.characters.size)
    val size = sizeText(c.sizeBytes)
    return when (c.action) {
        InstallAction.INSTALL -> {
            stringResource(R.string.import_action_install, characters, size)
        }

        InstallAction.UPDATE -> {
            stringResource(R.string.import_action_update, c.installedVersion.orEmpty(), c.version)
        }

        InstallAction.REINSTALL -> {
            stringResource(R.string.import_action_reinstall, c.version)
        }

        InstallAction.DOWNGRADE -> {
            stringResource(
                R.string.import_action_downgrade,
                c.installedVersion.orEmpty(),
                c.version,
            )
        }
    }
}

@Composable
private fun installLabel(state: ImportUi.Review): String {
    val review = state.review
    if (review.isBundle) {
        val count = state.selection.selected.size
        return pluralStringResource(R.plurals.import_install_packs, count, count)
    }
    return when (
        review.packs
            .single()
            .candidate.action
    ) {
        InstallAction.INSTALL -> stringResource(R.string.import_install)
        InstallAction.UPDATE -> stringResource(R.string.import_update)
        InstallAction.REINSTALL -> stringResource(R.string.import_reinstall)
        InstallAction.DOWNGRADE -> stringResource(R.string.import_downgrade)
    }
}

private const val REVEAL_STEP_MS = 110L
