package app.mauri.cucuto.ui.creator

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.creator.DraftCharacter
import app.mauri.cucuto.core.creator.DraftClip
import app.mauri.cucuto.core.creator.HapticMode
import app.mauri.cucuto.packs.parseSeedColor
import app.mauri.cucuto.ui.components.ChoiceButtonGroup
import java.io.File

/** Weights offered for a clip, with how they read. */
private val WEIGHTS =
    listOf(
        0.5 to R.string.creator_weight_rare,
        1.0 to R.string.creator_weight_normal,
        2.0 to R.string.creator_weight_often,
        4.0 to R.string.creator_weight_very_often,
    )

/** One character: picture, name, colors, clips (record or pick, label, weight) and vibration. In its own colors. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CharacterPage(
    character: DraftCharacter,
    suggestions: List<Int>,
    media: (String) -> File,
    canEditHaptic: Boolean,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onPickSprite: () -> Unit,
    onSeedColor: (Int?) -> Unit,
    onRecord: () -> Unit,
    onPickAudio: () -> Unit,
    onPlayClip: (String) -> Unit,
    onClipLabel: (String, String) -> Unit,
    onClipWeight: (String, Double) -> Unit,
    onRemoveClip: (String) -> Unit,
    onHaptic: (HapticMode) -> Unit,
    onEditHaptic: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    CharacterColors(character.seedColor) {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(character.name.ifBlank { stringResource(R.string.creator_character_new) }) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_back),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { confirmRemove = true }) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.creator_character_remove),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Picture and name.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SpriteBadge(
                        sprite = character.sprite?.let(media),
                        contentDescription = stringResource(R.string.creator_sprite),
                        modifier =
                            Modifier
                                .size(
                                    112.dp,
                                ).clickable(
                                    onClickLabel = stringResource(R.string.creator_sprite_choose),
                                    onClick = onPickSprite,
                                ),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = character.name,
                            onValueChange = onName,
                            label = { Text(stringResource(R.string.creator_character_name)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = onPickSprite, shapes = ButtonDefaults.shapes()) {
                            Icon(
                                painterResource(R.drawable.ic_creator_image),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Text(
                                stringResource(
                                    if (character.sprite ==
                                        null
                                    ) {
                                        R.string.creator_sprite_choose
                                    } else {
                                        R.string.creator_sprite_change
                                    },
                                ),
                                Modifier.padding(start = ButtonDefaults.IconSpacing),
                            )
                        }
                    }
                }

                // Colors.
                Section(stringResource(R.string.creator_colors), stringResource(R.string.creator_colors_hint)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val current = parseSeedColor(character.seedColor)
                        val swatches = (suggestions + listOfNotNull(current)).map { it or OPAQUE }.distinct()
                        swatches.forEach { argb ->
                            Swatch(
                                color = Color(argb),
                                selected = current?.let { it or OPAQUE } == argb,
                                onClick = { onSeedColor(argb) },
                            )
                        }
                        Swatch(color = null, selected = current == null, onClick = { onSeedColor(null) })
                    }
                }

                // Clips.
                Section(stringResource(R.string.creator_clips_title), stringResource(R.string.creator_clips_hint)) {
                    if (character.ringtones.isNotEmpty() || character.spriteAnimated != null) {
                        Text(
                            passThroughNote(character.ringtones.size, character.spriteAnimated != null),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // A song-only character from a duplicated pack is fine: the format asks for a clip or a ringtone.
                    if (character.clips.isEmpty() && character.ringtones.isEmpty()) {
                        Text(
                            stringResource(R.string.creator_clips_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    character.clips.forEach { clip ->
                        ClipRow(
                            clip = clip,
                            onPlay = { onPlayClip(clip.key) },
                            onLabel = { onClipLabel(clip.key, it) },
                            onWeight = { onClipWeight(clip.key, it) },
                            onRemove = { onRemoveClip(clip.key) },
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(onClick = onRecord, shapes = ButtonDefaults.shapes()) {
                            Icon(
                                painterResource(R.drawable.ic_creator_mic),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Text(
                                stringResource(R.string.creator_record),
                                Modifier.padding(start = ButtonDefaults.IconSpacing),
                            )
                        }
                        OutlinedButton(onClick = onPickAudio, shapes = ButtonDefaults.shapes()) {
                            Icon(
                                painterResource(R.drawable.ic_creator_audio_file),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Text(
                                stringResource(R.string.creator_pick_audio),
                                Modifier.padding(start = ButtonDefaults.IconSpacing),
                            )
                        }
                    }
                }

                // Vibration.
                Section(stringResource(R.string.creator_haptics), stringResource(hapticHint(character.haptic))) {
                    val modes =
                        buildList {
                            add(HapticMode.AUTO)
                            add(HapticMode.NONE)
                            if (canEditHaptic || character.haptic == HapticMode.CUSTOM) add(HapticMode.CUSTOM)
                        }
                    ChoiceButtonGroup(
                        options = modes,
                        selected = character.haptic,
                        onSelect = onHaptic,
                        label = { stringResource(hapticLabel(it)) },
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (character.clips.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { onPlayClip(character.clips.random().key) },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_creator_vibration),
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Text(
                                    stringResource(R.string.creator_haptic_try),
                                    Modifier.padding(start = ButtonDefaults.IconSpacing),
                                )
                            }
                        }
                        if (canEditHaptic && character.haptic == HapticMode.CUSTOM) {
                            TextButton(onClick = onEditHaptic) { Text(stringResource(R.string.creator_haptic_edit)) }
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = {
                Text(
                    stringResource(
                        R.string.creator_character_remove_title,
                        character.name.ifBlank {
                            stringResource(R.string.creator_character_unnamed)
                        },
                    ),
                )
            },
            text = { Text(stringResource(R.string.creator_character_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text(stringResource(R.string.creator_remove)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmRemove = false },
                ) { Text(stringResource(R.string.creator_cancel)) }
            },
        )
    }
}

/** What a duplicated pack's character keeps without the creator editing it (#185). */
@Composable
private fun passThroughNote(
    ringtones: Int,
    animated: Boolean,
): String =
    when {
        ringtones > 0 && animated -> {
            pluralStringResource(R.plurals.creator_kept_ringtones_and_animation, ringtones, ringtones)
        }

        ringtones > 0 -> {
            pluralStringResource(R.plurals.creator_kept_ringtones, ringtones, ringtones)
        }

        else -> {
            stringResource(R.string.creator_kept_animation)
        }
    }

@Composable
private fun Section(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** A seed color to pick; null is "automatic" (apps extract one from the sprite). */
@Composable
private fun Swatch(
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label =
        if (color ==
            null
        ) {
            stringResource(R.string.creator_color_auto)
        } else {
            stringResource(R.string.creator_color)
        }
    Surface(
        shape = CircleShape,
        color = color ?: MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier =
            modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ).clickable(role = Role.RadioButton, onClick = onClick)
                .semantics {
                    contentDescription = label
                    this.selected = selected
                },
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                selected -> {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint =
                            if (color ==
                                null
                            ) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.White
                            },
                    )
                }

                color == null -> {
                    // A fixed size inside the fixed circle: the swatch's content description says it in full.
                    Text(
                        stringResource(R.string.creator_color_auto_short),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = with(LocalDensity.current) { SWATCH_LABEL.toSp() },
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

/** One clip: play, label, how often it plays, length (with the gentle hint for long clips) and remove. */
@Composable
private fun ClipRow(
    clip: DraftClip,
    onPlay: () -> Unit,
    onLabel: (String) -> Unit,
    onWeight: (Double) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var weightMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.creator_clip_play))
            }
            OutlinedTextField(
                value = clip.label,
                onValueChange = onLabel,
                placeholder = { Text(stringResource(R.string.creator_clip_label)) },
                supportingText = { Text(stringResource(R.string.creator_seconds, seconds(clip.durationMs))) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { weightMenu = true }) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription =
                            stringResource(
                                R.string.creator_clip_weight,
                                stringResource(weightLabel(clip.weight)),
                            ),
                    )
                }
                DropdownMenu(expanded = weightMenu, onDismissRequest = { weightMenu = false }) {
                    WEIGHTS.forEach { (weight, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            leadingIcon =
                                if (clip.weight ==
                                    weight
                                ) {
                                    ({ Icon(Icons.Rounded.Check, contentDescription = null) })
                                } else {
                                    null
                                },
                            onClick = {
                                weightMenu = false
                                onWeight(weight)
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.creator_clip_remove))
            }
        }
        if (clip.durationMs > ClipLimits.HINT_MS) {
            Row(
                Modifier.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.creator_long_clip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun weightLabel(weight: Double): Int = WEIGHTS.minBy { kotlin.math.abs(it.first - weight) }.second

private fun hapticLabel(mode: HapticMode): Int =
    when (mode) {
        HapticMode.AUTO -> R.string.creator_haptic_auto
        HapticMode.NONE -> R.string.creator_haptic_none
        HapticMode.CUSTOM -> R.string.creator_haptic_custom
    }

private fun hapticHint(mode: HapticMode): Int =
    when (mode) {
        HapticMode.AUTO -> R.string.creator_haptic_auto_hint
        HapticMode.NONE -> R.string.creator_haptic_none_hint
        HapticMode.CUSTOM -> R.string.creator_haptic_custom_hint
    }

private const val OPAQUE = 0xFF000000.toInt()

private val SWATCH_LABEL = 11.dp
