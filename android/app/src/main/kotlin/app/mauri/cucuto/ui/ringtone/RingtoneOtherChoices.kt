package app.mauri.cucuto.ui.ringtone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.ui.components.isLargeFont

/** The ringtone choices that are not a pack character (ADR 0073), in display order. */
internal val OtherRingtoneChoices: List<RingtoneChoice> =
    listOf(RingtoneChoice.ANDROID_DEFAULT, RingtoneChoice.UNCHANGED)

internal val RingtoneChoice.title: Int
    get() =
        when (this) {
            RingtoneChoice.ANDROID_DEFAULT -> R.string.ringtone_choice_default
            RingtoneChoice.UNCHANGED -> R.string.ringtone_choice_unchanged
            RingtoneChoice.CHARACTER -> R.string.ringtone_picker_characters
        }

internal val RingtoneChoice.body: Int
    get() =
        when (this) {
            RingtoneChoice.ANDROID_DEFAULT -> R.string.ringtone_choice_default_body
            RingtoneChoice.UNCHANGED -> R.string.ringtone_choice_unchanged_body
            RingtoneChoice.CHARACTER -> R.string.ringtone_picker_hint
        }

internal val RingtoneChoice.icon: ImageVector
    get() =
        when (this) {
            RingtoneChoice.UNCHANGED -> Icons.Rounded.Lock
            RingtoneChoice.ANDROID_DEFAULT, RingtoneChoice.CHARACTER -> CallIcon
        }

/**
 * "Other choices" in the ringtone picker (ADR 0073): "Android default" and "Leave as is", as two compact filter chips
 * so the covers stay close; the chosen one's explanation is in the header ([RingtoneOtherHeader]). [chosen] is the
 * choice shown as chosen, if any.
 */
@Composable
internal fun RingtoneOtherChoices(
    chosen: RingtoneChoice?,
    onChoose: (RingtoneChoice) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.ringtone_choice_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            for (choice in OtherRingtoneChoices) {
                val selected = chosen == choice
                FilterChip(
                    selected = selected,
                    enabled = enabled,
                    onClick = { if (!selected) onChoose(choice) },
                    label = { Text(stringResource(choice.title)) },
                    leadingIcon = {
                        Icon(
                            choice.icon,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
    }
}

/** The picker's header while "Android default" or "Leave as is" is chosen: what it does, in place of a record. */
@Composable
internal fun RingtoneOtherHeader(
    choice: RingtoneChoice,
    modifier: Modifier = Modifier,
) {
    val text: @Composable (Modifier) -> Unit = { textModifier ->
        Column(textModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(choice.title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(choice.body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // With large fonts the words go under the icon, so "Predeterminado de Android" keeps whole words (#226).
    if (isLargeFont()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxWidth()) {
            ChoiceIcon(choice.icon, LARGE_FONT_ICON)
            text(Modifier)
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        ChoiceIcon(choice.icon, ICON)
        text(Modifier.weight(1f))
    }
}

private val ICON = 104.dp
private val LARGE_FONT_ICON = 72.dp

@Composable
private fun ChoiceIcon(
    icon: ImageVector,
    size: Dp,
) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}
