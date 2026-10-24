package app.mauri.cucuto.picker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.ui.components.BottomActionBar
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.theme.LocalReducedMotion

/**
 * A pack's sticky section header in a picker (ADR 0072): its name, its number of eligible characters, a "Surprise me"
 * dice for that pack and, when it folds, a chevron. A tap on the row folds or unfolds the pack.
 */
@Composable
fun PackSectionHeader(
    section: PackSection<*>,
    onToggle: () -> Unit,
    onSurprise: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val stateLabel =
        stringResource(if (section.expanded) R.string.picker_pack_unfolded else R.string.picker_pack_folded)
    val count = pluralStringResource(R.plurals.picker_pack_count, section.total, section.total)
    val toggle =
        if (section.foldable) {
            Modifier
                .clickable(
                    onClickLabel =
                        stringResource(
                            if (section.expanded) R.string.picker_pack_fold else R.string.picker_pack_unfold,
                        ),
                    role = Role.Button,
                    onClick = onToggle,
                ).semantics { stateDescription = stateLabel }
        } else {
            Modifier
        }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .then(toggle)
                        .padding(start = 4.dp, end = 4.dp)
                        .semantics(mergeDescendants = true) {
                            heading()
                            contentDescription = "${section.name}, $count"
                        },
            ) {
                Text(
                    text = section.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = adaptiveMaxLines(1),
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = section.total.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (section.foldable) {
                    val turn by animateFloatAsState(
                        targetValue = if (section.expanded) 180f else 0f,
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                        label = "chevron",
                    )
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(turn),
                    )
                }
            }
            SurpriseButton(
                label = stringResource(R.string.picker_surprise_pack, section.name),
                onClick = onSurprise,
                enabled = enabled,
            )
        }
    }
}

/** The dice icon button that picks a random character ("Surprise me", ADR 0072). */
@Composable
fun SurpriseButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(DiceIcon, contentDescription = label)
    }
}

/**
 * The bar a "Surprise me" pick brings up (ADR 0072): **Re-roll**, and [useLabel] ("Use Bleep the frog") to confirm
 * when the picker can assign.
 */
@Composable
fun SurpriseBar(
    useLabel: String?,
    onReroll: () -> Unit,
    onUse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomActionBar(modifier) {
        RerollButton(onReroll)
        if (useLabel != null) {
            Button(onClick = onUse, shapes = ButtonDefaults.shapes()) { Text(useLabel) }
        }
    }
}

/** "Re-roll": another "Surprise me" pick from the same packs, never the same character twice in a row. */
@Composable
fun RerollButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier) {
        DiceGlyph()
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.picker_reroll))
    }
}

/**
 * Scrolls [grid] to each new "Surprise me" pick ([rollCount] changes): [index] is the pick's grid index, null when it
 * is not shown. It lands just below the pack's sticky header; it jumps instead of gliding with animations removed.
 */
@Composable
fun PickerScrollToRoll(
    grid: LazyGridState,
    rollCount: Int?,
    index: () -> Int?,
) {
    val reduced = LocalReducedMotion.current
    val headerPx = with(LocalDensity.current) { STICKY_HEADER_ROOM.toPx() }
    val target by rememberUpdatedState(index)
    LaunchedEffect(rollCount) {
        if (rollCount == null) return@LaunchedEffect
        val at = target() ?: return@LaunchedEffect
        if (reduced) {
            grid.scrollToItem(at)
            grid.scrollBy(-headerPx)
        } else {
            grid.animateScrollToItem(at)
            grid.animateScrollBy(-headerPx)
        }
    }
}

/** Room for a pack's sticky header above a character the grid scrolls to. */
private val STICKY_HEADER_ROOM = 64.dp

/** The pickers' search field (ADR 0072): filters in place, ignoring case and accents; finds folded characters too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    Surface(
        shape = SearchBarDefaults.inputFieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = { focus.clearFocus() },
            expanded = false,
            onExpandedChange = {},
            placeholder = { Text(stringResource(R.string.picker_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.picker_search_clear))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "No character matches" under the search field. */
@Composable
fun PickerNoMatches(
    query: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.picker_search_empty, query.trim()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 16.dp),
    )
}

/** A dice inside a text row: the "Surprise me" glyph for buttons with a label. */
@Composable
fun DiceGlyph(modifier: Modifier = Modifier) {
    Icon(DiceIcon, contentDescription = null, modifier = modifier.size(18.dp).clearAndSetSemantics {})
}

/**
 * `animateItem()` for the pickers' grid cells, so folding and unfolding fade and slide them; no motion when animations
 * are removed (the state still changes).
 */
@Composable
fun LazyGridItemScope.animateCellUnlessReduced(modifier: Modifier = Modifier): Modifier =
    if (LocalReducedMotion.current) {
        modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    } else {
        modifier.animateItem(
            fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        )
    }

/** Material "casino" (rounded), missing from `material-icons-core`; path data from Material Icons (Apache-2.0). */
val DiceIcon: ImageVector by lazy {
    ImageVector
        .Builder("Dice", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData =
                addPathNodes(
                    "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM7.5 18c-.83 0-1.5" +
                        "-.67-1.5-1.5S6.67 15 7.5 15s1.5.67 1.5 1.5S8.33 18 7.5 18zm0-9C6.67 9 6 8.33 6 7.5S6.67 6 " +
                        "7.5 6 9 6.67 9 7.5 8.33 9 7.5 9zm4.5 4.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 " +
                        "1.5-.67 1.5-1.5 1.5zm4.5 4.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5" +
                        "-1.5 1.5zm0-9c-.83 0-1.5-.67-1.5-1.5S15.67 6 16.5 6s1.5.67 1.5 1.5S17.33 9 16.5 9z",
                ),
            fill = SolidColor(Color.Black),
        ).build()
}
