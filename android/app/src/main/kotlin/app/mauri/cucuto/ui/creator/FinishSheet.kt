package app.mauri.cucuto.ui.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.pack.ImportProgress
import app.mauri.cucuto.packs.ImportProblem
import app.mauri.cucuto.packs.ProblemItem
import app.mauri.cucuto.packspec.authoring.DraftProblem
import app.mauri.cucuto.ui.components.isLargeFont

/**
 * "Check and finish": the pack is built and run through the same checks as the importer, packer and the website.
 * Then install it into the library (the importer's own pipeline), save it as a file or share it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinishSheet(
    finish: FinishUi,
    onInstall: () -> Unit,
    onExport: (fileName: String) -> Unit,
    onShare: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        FinishContent(finish, onInstall, onExport, onShare, onDone, onDismiss)
    }
}

/**
 * What the "Check and finish" sheet shows, without the sheet: the accessibility checks and large-font goldens render
 * it in the main window, which has their font size and language (a sheet's own window does not, on Robolectric).
 */
@Composable
internal fun FinishContent(
    finish: FinishUi,
    onInstall: () -> Unit,
    onExport: (fileName: String) -> Unit,
    onShare: () -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (finish) {
            FinishUi.Checking -> {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(56.dp))
                }
                Text(stringResource(R.string.creator_checking), style = MaterialTheme.typography.titleMedium)
            }

            is FinishUi.Incomplete -> {
                Text(
                    stringResource(R.string.creator_incomplete_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                finish.problems.forEach { Line(draftProblemText(it), warning = true) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.creator_keep_editing)) }
            }

            is FinishUi.Invalid -> {
                InvalidContent(finish.problem)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.creator_keep_editing)) }
            }

            is FinishUi.Ready -> {
                ReadyContent(finish, onInstall, onExport, onShare, onDone)
            }
        }
    }
}

@Composable
private fun ReadyContent(
    ready: FinishUi.Ready,
    onInstall: () -> Unit,
    onExport: (String) -> Unit,
    onShare: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val manifest = ready.manifest
        val clips = manifest.characters.sumOf { it.clips.size }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Text(
                stringResource(R.string.creator_ready_title, manifest.name),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Text(
            stringResource(
                R.string.creator_ready_summary,
                pluralStringResource(R.plurals.creator_characters, manifest.characters.size, manifest.characters.size),
                pluralStringResource(R.plurals.creator_clips, clips, clips),
                manifest.version,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.creator_ready_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ready.warnings.forEach { Line(warningText(it), warning = false) }

        when {
            ready.installing -> {
                Text(stringResource(R.string.creator_installing), style = MaterialTheme.typography.titleMedium)
                val fraction = ready.progress?.let(::fractionOf)
                if (fraction != null) {
                    LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            ready.installed -> {
                Line(stringResource(R.string.creator_installed_line), warning = false, done = true)
                Button(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.creator_done))
                }
            }

            else -> {
                Button(onClick = onInstall, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.creator_install), Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
        if (!ready.installing) {
            // Side by side, or one under the other with large fonts, so "Share" is never cut mid-word (#226).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = if (isLargeFont()) 1 else 2,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = {
                    onExport(ready.fileName)
                }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                    Icon(
                        painterResource(R.drawable.ic_creator_download),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.creator_export), Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
                OutlinedButton(onClick = onShare, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.creator_share), Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
            Text(
                stringResource(R.string.creator_file_name, ready.fileName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InvalidContent(
    problem: ImportProblem,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.creator_invalid_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(problem.summary), style = MaterialTheme.typography.bodyLarge)
        problem.items.forEach {
            Line(
                stringResource(it.message) + (
                    it.file?.let { f ->
                        " ($f)"
                    } ?: ""
                ),
                warning = true,
            )
        }
        if (problem.details.isNotEmpty()) {
            Text(
                problem.details.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Line(
    text: String,
    warning: Boolean,
    modifier: Modifier = Modifier,
    done: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** How far an install step is, when it counts something. */
private fun fractionOf(progress: ImportProgress): Float? {
    fun of(
        done: Number,
        total: Number?,
    ) = total?.toFloat()?.takeIf { it > 0f }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
    return when (progress) {
        is ImportProgress.Copying -> of(progress.bytesCopied, progress.totalBytes)
        is ImportProgress.Extracting -> of(progress.done, progress.total)
        is ImportProgress.Decoding -> of(progress.done, progress.total)
        is ImportProgress.Analyzing -> of(progress.done, progress.total)
        is ImportProgress.Installing -> of(progress.done, progress.total)
        ImportProgress.Validating -> null
    }
}

@Composable
private fun warningText(item: ProblemItem): String = stringResource(item.message) + (item.file?.let { " ($it)" } ?: "")

/** What a draft still needs, in plain words, naming the character it concerns. */
@Composable
private fun draftProblemText(problem: DraftProblem): String {
    val who = problem.character?.let { stringResource(R.string.creator_character_number, it + 1) }.orEmpty()
    return when (problem.kind) {
        DraftProblem.Kind.PACK_NAME_MISSING -> {
            stringResource(R.string.creator_problem_name)
        }

        DraftProblem.Kind.AUTHOR_MISSING -> {
            stringResource(R.string.creator_problem_author)
        }

        DraftProblem.Kind.LICENSE_MISSING -> {
            stringResource(R.string.creator_problem_license)
        }

        DraftProblem.Kind.NO_CHARACTERS -> {
            stringResource(R.string.creator_problem_no_characters)
        }

        DraftProblem.Kind.TOO_MANY_CHARACTERS -> {
            stringResource(R.string.creator_problem_too_many_characters)
        }

        DraftProblem.Kind.CHARACTER_NAME_MISSING -> {
            stringResource(R.string.creator_problem_character_name, who)
        }

        DraftProblem.Kind.SPRITE_MISSING -> {
            stringResource(R.string.creator_problem_sprite, who)
        }

        DraftProblem.Kind.NO_CLIPS -> {
            stringResource(R.string.creator_problem_clips, who)
        }

        DraftProblem.Kind.TOO_MANY_CLIPS -> {
            stringResource(R.string.creator_problem_too_many_clips, who)
        }

        DraftProblem.Kind.UNSUPPORTED_FILE -> {
            if (problem.character ==
                null
            ) {
                stringResource(R.string.creator_problem_cover_file)
            } else {
                stringResource(R.string.creator_problem_file, who)
            }
        }
    }
}
