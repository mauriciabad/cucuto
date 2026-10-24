package app.mauri.cucuto.packs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.packs.LibraryPack

/** Everything about one installed pack: all its characters, where it came from, who uses it, and Remove. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PackDetailSheet(
    pack: LibraryPack,
    player: ClipPlayer,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onDuplicate: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PackCover(pack, Modifier.size(72.dp))
                Column(Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.headlineSmall)
                    pack.author?.let {
                        Text(
                            stringResource(R.string.packs_by_author, it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            pack.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }

            Text(
                pluralStringResource(R.plurals.packs_characters, pack.characters.size, pack.characters.size),
                style = MaterialTheme.typography.titleMedium,
            )
            if (pack.filesMissing) {
                Text(
                    stringResource(R.string.packs_files_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pack.characters.forEach { character ->
                        CharacterBadge(character, playerKey = "${pack.id}/${character.id}", player = player)
                    }
                }
            }

            HorizontalDivider()
            Fact(stringResource(R.string.packs_fact_version), pack.version)
            pack.license?.let { Fact(stringResource(R.string.packs_fact_license), it) }
            // The recorded size is the imported file's, so it says little about one pack of a bundle.
            if (pack.sizeBytes > 0 &&
                pack.bundleId == null
            ) {
                Fact(stringResource(R.string.packs_fact_size), sizeText(pack.sizeBytes))
            }
            Fact(stringResource(R.string.packs_fact_source), stringResource(sourceText(pack.source)))
            Fact(stringResource(R.string.packs_fact_contacts), contactsText(pack))
            Text(
                stringResource(R.string.packs_update_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onDuplicate != null && !pack.filesMissing) {
                OutlinedButton(onClick = onDuplicate, shapes = ButtonDefaults.shapes()) {
                    Icon(
                        Icons.Rounded.Create,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.packs_duplicate), Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
                Text(
                    stringResource(R.string.packs_duplicate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onRemove,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.packs_remove), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

@Composable
private fun Fact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            label,
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun sourceText(source: PackSource): Int =
    when (source) {
        PackSource.LOCAL -> R.string.packs_source_local
        PackSource.URL -> R.string.packs_source_url
        PackSource.REPOSITORY -> R.string.packs_source_repository
    }

@Composable
private fun contactsText(pack: LibraryPack): String =
    if (pack.contacts.isEmpty()) {
        stringResource(R.string.packs_contacts_none)
    } else {
        contactNames(pack)
    }

/** "Mom, Ana and 2 more" (or "3 contacts" when none has a name yet). */
@Composable
internal fun contactNames(pack: LibraryPack): String {
    val named = pack.contacts.mapNotNull { it.name }.distinct()
    val shown = named.take(MAX_NAMES)
    val others = pack.contacts.size - shown.size
    return when {
        shown.isEmpty() -> pluralStringResource(R.plurals.packs_contacts, others, others)
        others == 0 -> shown.joinToString(", ")
        else -> pluralStringResource(R.plurals.packs_names_and_more, others, shown.joinToString(", "), others)
    }
}

private const val MAX_NAMES = 3
