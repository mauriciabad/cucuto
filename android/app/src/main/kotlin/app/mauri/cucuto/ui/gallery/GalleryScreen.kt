package app.mauri.cucuto.ui.gallery

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.createBitmap
import androidx.graphics.shapes.RoundedPolygon
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.settings.UiSoundLevel
import app.mauri.cucuto.ui.components.CookieAvatar
import app.mauri.cucuto.ui.components.CuckooDoor
import app.mauri.cucuto.ui.components.ParticleBurst
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.CharacterTheme
import app.mauri.cucuto.ui.theme.CucutoSeed
import app.mauri.cucuto.ui.theme.CucutoShapes
import app.mauri.cucuto.ui.theme.CucutoTheme
import app.mauri.cucuto.ui.theme.LocalReducedMotion
import app.mauri.cucuto.ui.theme.LocalThemeConditions
import app.mauri.cucuto.ui.theme.extractSeedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Design gallery (debug builds only, linked from Status): every building block from #22 and #23 in one place, so
 * screen work can see and feel them. Not a product screen.
 */
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val soundLevel by viewModel.soundLevel.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    var dynamicColor by rememberSaveable { mutableStateOf(true) }
    CucutoTheme(dynamicColor = dynamicColor) {
        GalleryContent(
            settings =
                GallerySettings(
                    soundLevel = soundLevel,
                    hapticsEnabled = hapticsEnabled,
                    dynamicColor = dynamicColor,
                    richHaptics = viewModel.richHaptics,
                ),
            onSoundLevel = viewModel::setSoundLevel,
            onHaptics = viewModel::setHapticsEnabled,
            onDynamicColor = { dynamicColor = it },
            onBack = onBack,
            modifier = modifier,
        )
    }
}

private data class GallerySettings(
    val soundLevel: UiSoundLevel,
    val hapticsEnabled: Boolean,
    val dynamicColor: Boolean,
    val richHaptics: Boolean,
)

/** A sample character accent (brand/tokens/colors.json, "character"). */
private data class Accent(
    @param:StringRes val name: Int,
    val seed: Color?,
)

private val Accents =
    listOf(
        Accent(R.string.gallery_character_cucuto, null),
        Accent(R.string.gallery_character_leaf, Color(0xFF3FAE5A)),
        Accent(R.string.gallery_character_sky, Color(0xFF3D8BFF)),
        Accent(R.string.gallery_character_berry, Color(0xFFD9468F)),
        Accent(R.string.gallery_character_plum, Color(0xFF8E5CFF)),
        Accent(R.string.gallery_character_sunny, Color(0xFFFFC233)),
        Accent(R.string.gallery_character_slate, Color(0xFF5F7A8A)),
    )

private const val SPRITE_INDEX = -1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryContent(
    settings: GallerySettings,
    onSoundLevel: (UiSoundLevel) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    var accentIndex by rememberSaveable { mutableIntStateOf(0) }
    val sprite by produceState<Bitmap?>(null) { value = withContext(Dispatchers.Default) { sampleSprite() } }
    val spriteSeed by produceState<Color?>(null, sprite) { value = sprite?.let { extractSeedColor(it) } }
    val seed = if (accentIndex == SPRITE_INDEX) spriteSeed else Accents[accentIndex].seed

    CharacterTheme(seed = seed) {
        GalleryScaffold(
            settings = settings,
            accentIndex = accentIndex,
            sprite = sprite,
            spriteSeed = spriteSeed,
            onSelectAccent = {
                accentIndex = it
                feedback.fire(Feedback.Tap)
            },
            onSoundLevel = onSoundLevel,
            onHaptics = onHaptics,
            onDynamicColor = onDynamicColor,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScaffold(
    settings: GallerySettings,
    accentIndex: Int,
    sprite: Bitmap?,
    spriteSeed: Color?,
    onSelectAccent: (Int) -> Unit,
    onSoundLevel: (UiSoundLevel) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_gallery)) },
                subtitle = { Text(stringResource(R.string.gallery_subtitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DoorCard() }
            // Drawn above its neighbors so the burst flies over them.
            item { AssignCard(sprite, Modifier.zIndex(1f)) }
            item { SectionHeader(R.string.gallery_characters_title) }
            item { CharactersCard(accentIndex, sprite, spriteSeed, onSelectAccent) }
            item { SectionHeader(R.string.gallery_events_title) }
            item { EventsCard() }
            item { SectionHeader(R.string.gallery_settings_title) }
            item { SettingsCard(settings, onSoundLevel, onHaptics, onDynamicColor) }
            item { SectionHeader(R.string.gallery_colors_title) }
            item { ColorRoles(MaterialTheme.colorScheme) }
            item { SectionHeader(R.string.gallery_shapes_title) }
            item { ShapesCard() }
            item { SectionHeader(R.string.gallery_type_title) }
            item { TypeCard() }
        }
    }
}

@Composable
private fun SectionHeader(
    @StringRes text: Int,
) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .padding(start = 8.dp, top = 12.dp)
                .semantics { heading() },
    )
}

@Composable
private fun GalleryCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun DoorCard() {
    val feedback = LocalFeedback.current
    var beat by remember { mutableStateOf<FeedbackBeat?>(null) }
    GalleryCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CuckooDoor(
                beat = beat,
                closeAfterMillis = DOOR_STAYS_OPEN_MS,
                contentDescription = stringResource(R.string.gallery_door_description),
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
            )
        }
        Text(stringResource(R.string.gallery_door_title), style = MaterialTheme.typography.headlineSmallEmphasized)
        Text(stringResource(R.string.gallery_door_body), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { beat = feedback.fire(Feedback.DoorOpen) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.gallery_door_play))
        }
    }
}

private const val DOOR_STAYS_OPEN_MS = 2400L

/** Not a [Card]: a card clips its content, and the burst must fly past the edges. */
@Composable
private fun AssignCard(
    sprite: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    var beat by remember { mutableStateOf<FeedbackBeat?>(null) }
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                CookieAvatar(
                    modifier = Modifier.size(88.dp),
                    beat = beat,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    onClick = { beat = feedback.fire(Feedback.Assign("gallery")) },
                    onClickLabel = stringResource(R.string.gallery_assign_avatar),
                ) {
                    sprite?.let {
                        Image(
                            bitmap = remember(it) { it.asImageBitmap() },
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }
                ParticleBurst(beat = beat, modifier = Modifier.requiredSize(360.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.gallery_assign_title),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.gallery_assign_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharactersCard(
    selected: Int,
    sprite: Bitmap?,
    spriteSeed: Color?,
    onSelect: (Int) -> Unit,
) {
    GalleryCard {
        Text(stringResource(R.string.gallery_characters_body), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Accents.forEachIndexed { index, accent ->
                FilterChip(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    label = { Text(stringResource(accent.name)) },
                    leadingIcon = { Swatch(accent.seed ?: CucutoSeed) },
                )
            }
            FilterChip(
                selected = selected == SPRITE_INDEX,
                onClick = { onSelect(SPRITE_INDEX) },
                label = { Text(stringResource(R.string.gallery_character_sprite)) },
                enabled = spriteSeed != null,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            sprite?.let {
                Image(
                    bitmap = remember(it) { it.asImageBitmap() },
                    contentDescription = stringResource(R.string.gallery_sprite_description),
                    modifier = Modifier.size(56.dp),
                )
            }
            Text(
                text =
                    spriteSeed?.let { stringResource(R.string.gallery_sprite_seed, it.hex()) }
                        ?: stringResource(R.string.gallery_sprite_extracting),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            spriteSeed?.let { Swatch(it, Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(shape = CucutoShapes.Avatar.toShape(), color = color, modifier = modifier.size(18.dp)) {}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventsCard() {
    val feedback = LocalFeedback.current
    var toggled by rememberSaveable { mutableStateOf(false) }
    GalleryCard {
        Text(stringResource(R.string.gallery_events_body), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EventButton(R.string.gallery_event_tap) { feedback.fire(Feedback.Tap) }
            EventButton(R.string.gallery_event_confirm) { feedback.fire(Feedback.Confirm) }
            EventButton(R.string.gallery_event_reject) { feedback.fire(Feedback.Reject) }
            EventButton(R.string.gallery_event_success, tonal = true) { feedback.fire(Feedback.Success) }
            EventButton(R.string.gallery_event_error, tonal = true) { feedback.fire(Feedback.Error) }
            EventButton(R.string.gallery_event_long_press) { feedback.fire(Feedback.LongPress) }
            EventButton(R.string.gallery_event_tick) { feedback.fire(Feedback.SegmentTick) }
        }
        SettingRow(title = R.string.gallery_event_toggle) {
            Switch(
                checked = toggled,
                onCheckedChange = {
                    toggled = it
                    feedback.fire(if (it) Feedback.ToggleOn else Feedback.ToggleOff)
                },
            )
        }
    }
}

@Composable
private fun EventButton(
    @StringRes label: Int,
    tonal: Boolean = false,
    onClick: () -> Unit,
) {
    if (tonal) {
        FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(stringResource(label)) }
    } else {
        OutlinedButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(stringResource(label)) }
    }
}

@Composable
private fun SettingsCard(
    settings: GallerySettings,
    onSoundLevel: (UiSoundLevel) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val contrast = LocalThemeConditions.current.contrast
    GalleryCard {
        Text(stringResource(R.string.gallery_ui_sounds), style = MaterialTheme.typography.titleSmall)
        val levels = UiSoundLevel.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            levels.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = settings.soundLevel == level,
                    onClick = { onSoundLevel(level) },
                    shape = SegmentedButtonDefaults.itemShape(index, levels.size),
                ) { Text(stringResource(level.label())) }
            }
        }
        SettingRow(R.string.gallery_ui_haptics, stringResource(R.string.gallery_ui_haptics_body)) {
            Switch(checked = settings.hapticsEnabled, onCheckedChange = onHaptics)
        }
        SettingRow(R.string.gallery_dynamic_color, stringResource(R.string.gallery_dynamic_color_body)) {
            Switch(checked = settings.dynamicColor, onCheckedChange = onDynamicColor)
        }
        SettingRow(
            R.string.gallery_reduced_motion,
            stringResource(if (reduced) R.string.gallery_reduced_motion_on else R.string.gallery_reduced_motion_off),
        )
        SettingRow(
            R.string.gallery_rich_haptics,
            stringResource(
                if (settings.richHaptics) R.string.gallery_rich_haptics_yes else R.string.gallery_rich_haptics_no,
            ),
        )
        SettingRow(R.string.gallery_contrast, contrast.toString())
    }
}

private fun UiSoundLevel.label(): Int =
    when (this) {
        UiSoundLevel.Off -> R.string.gallery_ui_sounds_off
        UiSoundLevel.Quiet -> R.string.gallery_ui_sounds_quiet
        UiSoundLevel.Normal -> R.string.gallery_ui_sounds_normal
    }

@Composable
private fun SettingRow(
    @StringRes title: Int,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorRoles(scheme: ColorScheme) {
    val roles =
        listOf(
            Triple("primary", scheme.primary, scheme.onPrimary),
            Triple("primaryContainer", scheme.primaryContainer, scheme.onPrimaryContainer),
            Triple("secondary", scheme.secondary, scheme.onSecondary),
            Triple("secondaryContainer", scheme.secondaryContainer, scheme.onSecondaryContainer),
            Triple("tertiary", scheme.tertiary, scheme.onTertiary),
            Triple("tertiaryContainer", scheme.tertiaryContainer, scheme.onTertiaryContainer),
            Triple("error", scheme.error, scheme.onError),
            Triple("errorContainer", scheme.errorContainer, scheme.onErrorContainer),
            Triple("surfaceContainerHighest", scheme.surfaceContainerHighest, scheme.onSurface),
            Triple("inverseSurface", scheme.inverseSurface, scheme.inverseOnSurface),
        )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2,
    ) {
        roles.forEach { (name, container, content) ->
            Surface(
                color = container,
                contentColor = content,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Text(container.hex(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShapesCard() {
    val shapes: List<Pair<Int, RoundedPolygon>> =
        listOf(
            R.string.gallery_shape_avatar to CucutoShapes.Avatar,
            R.string.gallery_shape_avatar_active to CucutoShapes.AvatarActive,
            R.string.gallery_shape_tile to CucutoShapes.TilePressed,
            R.string.gallery_shape_badge to CucutoShapes.Badge,
            R.string.gallery_shape_door to CucutoShapes.DoorFrame,
        )
    GalleryCard {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            shapes.forEach { (label, polygon) ->
                ShapeSample(label) {
                    Surface(
                        shape = polygon.toShape(),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp),
                    ) {}
                }
            }
            ShapeSample(R.string.gallery_shape_particles) {
                Row(
                    Modifier.size(width = 72.dp, height = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val colors =
                        with(
                            MaterialTheme.colorScheme,
                        ) { listOf(primary, primaryContainer, tertiary, tertiaryContainer) }
                    CucutoShapes.Particles.forEachIndexed { i, polygon ->
                        Surface(shape = polygon.toShape(), color = colors[i], modifier = Modifier.size(14.dp)) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun ShapeSample(
    @StringRes label: Int,
    content: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TypeCard() {
    GalleryCard {
        Text(stringResource(R.string.gallery_type_display), style = MaterialTheme.typography.displayMediumEmphasized)
        Text(stringResource(R.string.gallery_type_headline), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.gallery_type_title_sample), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.gallery_type_body), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.gallery_type_label), style = MaterialTheme.typography.labelLarge)
    }
}

private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/** A small frog drawn in code: a stand-in sprite with a transparent background, to show seed extraction. */
private fun sampleSprite(): Bitmap {
    val size = SPRITE_PX
    val bitmap = createBitmap(size, size)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    fun circle(
        x: Float,
        y: Float,
        r: Float,
        color: Long,
    ) {
        paint.color = color.toInt()
        canvas.drawCircle(x * size, y * size, r * size, paint)
    }
    circle(0.5f, 0.6f, 0.36f, 0xFF2E9E4FL)
    circle(0.5f, 0.7f, 0.2f, 0xFFDDF2A0L)
    circle(0.32f, 0.3f, 0.13f, 0xFF2E9E4FL)
    circle(0.68f, 0.3f, 0.13f, 0xFF2E9E4FL)
    circle(0.32f, 0.3f, 0.08f, 0xFFFFFFFFL)
    circle(0.68f, 0.3f, 0.08f, 0xFFFFFFFFL)
    circle(0.33f, 0.31f, 0.04f, 0xFF1B1B1BL)
    circle(0.67f, 0.31f, 0.04f, 0xFF1B1B1BL)
    return bitmap
}

private const val SPRITE_PX = 160

@Preview(showBackground = true, heightDp = 1800)
@Composable
private fun GalleryPreview() {
    CucutoTheme(dynamicColor = false) {
        GalleryContent(
            settings =
                GallerySettings(
                    UiSoundLevel.Quiet,
                    hapticsEnabled = true,
                    dynamicColor = false,
                    richHaptics = true,
                ),
            onSoundLevel = {},
            onHaptics = {},
            onDynamicColor = {},
            onBack = {},
        )
    }
}
