package app.mauri.cucuto.ui.creator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.creator.CreatorDraft
import app.mauri.cucuto.core.creator.DraftCharacter
import app.mauri.cucuto.packspec.authoring.Authoring
import coil3.compose.AsyncImage
import java.io.File

/** Licenses offered as chips; anything else is typed in. The personal-use text is written as is (free text). */
private val LICENSE_CHIPS = listOf(Authoring.PERSONAL_USE_LICENSE) + Authoring.LICENSE_PRESETS

/** The creator's first page: the pack's details, its characters, and "Check and finish". */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PackPage(
    draft: CreatorDraft,
    media: (String) -> File,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onAuthor: (String) -> Unit,
    onLicense: (String) -> Unit,
    onDescription: (String) -> Unit,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onAddCharacter: () -> Unit,
    onCheck: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var menu by remember { mutableStateOf(false) }
    var confirmStartOver by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        if (draft.copiedFrom !=
                            null
                        ) {
                            stringResource(R.string.creator_title_copy)
                        } else {
                            stringResource(R.string.creator_title)
                        },
                    )
                },
                subtitle = draft.copiedFrom?.let { { Text(stringResource(R.string.creator_subtitle_copy, it)) } },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.creator_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.creator_start_over)) },
                                onClick = {
                                    menu = false
                                    confirmStartOver = true
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = onCheck,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.creator_check), Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.creator_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "details") {
                DetailsCard(draft, media, onName, onAuthor, onLicense, onDescription, onPickCover, onRemoveCover)
            }
            item(key = "characters-title") {
                Text(
                    pluralStringResource(R.plurals.creator_characters, draft.characters.size, draft.characters.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (draft.characters.isEmpty()) {
                item(key = "characters-empty") {
                    Text(
                        stringResource(R.string.creator_characters_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(draft.characters, key = { it.key }) { character ->
                CharacterCard(
                    character,
                    media,
                    onOpen = { onOpenCharacter(character.key) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "add") {
                FilledTonalButton(onClick = onAddCharacter, shapes = ButtonDefaults.shapes()) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(
                        stringResource(R.string.creator_add_character),
                        Modifier.padding(start = ButtonDefaults.IconSpacing),
                    )
                }
            }
        }
    }

    if (confirmStartOver) {
        AlertDialog(
            onDismissRequest = { confirmStartOver = false },
            title = { Text(stringResource(R.string.creator_start_over_title)) },
            text = { Text(stringResource(R.string.creator_start_over_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStartOver = false
                    onStartOver()
                }) { Text(stringResource(R.string.creator_start_over_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStartOver = false }) { Text(stringResource(R.string.creator_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsCard(
    draft: CreatorDraft,
    media: (String) -> File,
    onName: (String) -> Unit,
    onAuthor: (String) -> Unit,
    onLicense: (String) -> Unit,
    onDescription: (String) -> Unit,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // "Other" stays open while the user types a license that happens to match a chip.
    var otherLicense by rememberSaveable { mutableStateOf(draft.license !in LICENSE_CHIPS) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.creator_pack_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.author,
                onValueChange = onAuthor,
                label = { Text(stringResource(R.string.creator_author)) },
                supportingText = { Text(stringResource(R.string.creator_author_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = onDescription,
                label = { Text(stringResource(R.string.creator_description)) },
                minLines = 2,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.creator_license), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LICENSE_CHIPS.forEach { license ->
                    val selected = !otherLicense && draft.license == license
                    FilterChip(
                        selected = selected,
                        onClick = {
                            otherLicense = false
                            onLicense(license)
                        },
                        label = { Text(stringResource(licenseLabel(license))) },
                        leadingIcon =
                            if (selected) {
                                {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
                FilterChip(
                    selected = otherLicense,
                    onClick = {
                        if (!otherLicense) onLicense("")
                        otherLicense = true
                    },
                    label = { Text(stringResource(R.string.creator_license_other)) },
                )
            }
            if (otherLicense) {
                OutlinedTextField(
                    value = draft.license,
                    onValueChange = onLicense,
                    label = { Text(stringResource(R.string.creator_license_custom)) },
                    supportingText = { Text(stringResource(R.string.creator_license_custom_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    stringResource(licenseExplanation(draft.license)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(stringResource(R.string.creator_cover), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val cover = draft.cover
                if (cover != null) {
                    AsyncImage(
                        model = media(cover),
                        contentDescription = stringResource(R.string.creator_cover),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
                    )
                } else {
                    Surface(
                        shape = MaterialShapes.Cookie4Sided.toShape(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(72.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_creator_image), contentDescription = null)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.creator_cover_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPickCover, shapes = ButtonDefaults.shapes()) {
                            Text(
                                stringResource(
                                    if (cover ==
                                        null
                                    ) {
                                        R.string.creator_cover_add
                                    } else {
                                        R.string.creator_cover_change
                                    },
                                ),
                            )
                        }
                        if (cover != null) {
                            TextButton(onClick = onRemoveCover) { Text(stringResource(R.string.creator_remove)) }
                        }
                    }
                }
            }
        }
    }
}

/** A character on the pack page: its picture in its colors, its name and how many clips it has. */
@Composable
private fun CharacterCard(
    character: DraftCharacter,
    media: (String) -> File,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CharacterColors(character.seedColor) {
        Card(
            onClick = onOpen,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SpriteBadge(character.sprite?.let(media), Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        character.name.ifBlank { stringResource(R.string.creator_character_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        characterSummary(character),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (character.sprite == null || character.clips.isEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun characterSummary(character: DraftCharacter): String =
    when {
        character.sprite == null -> stringResource(R.string.creator_character_needs_picture)
        character.clips.isEmpty() -> stringResource(R.string.creator_character_needs_clips)
        else -> pluralStringResource(R.plurals.creator_clips, character.clips.size, character.clips.size)
    }

private fun licenseLabel(license: String): Int =
    when (license) {
        "CC0-1.0" -> R.string.creator_license_cc0
        "CC-BY-4.0" -> R.string.creator_license_cc_by
        else -> R.string.creator_license_personal
    }

private fun licenseExplanation(license: String): Int =
    when (license) {
        "CC0-1.0" -> R.string.creator_license_cc0_hint
        "CC-BY-4.0" -> R.string.creator_license_cc_by_hint
        else -> R.string.creator_license_personal_hint
    }
