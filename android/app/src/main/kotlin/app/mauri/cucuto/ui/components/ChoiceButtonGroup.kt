package app.mauri.cucuto.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/**
 * A single choice as a connected button group (Material 3 Expressive). When a label would not fit its equal share of
 * the width (large fonts, longer translations), the options wrap as separate buttons instead, so no label is ever
 * clipped. Each option reads as a radio button in a group to TalkBack. Outlined, so the options stay visible on a
 * card of the same color as a filled button's container (non-text contrast, WCAG 1.4.11).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceButtonGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val labels = options.map { label(it) }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gaps = ButtonGroupDefaults.ConnectedSpaceBetween * (options.size - 1)
        val share = with(density) { ((maxWidth - gaps) / options.size - LABEL_PADDING).toPx() }
        val fits = labels.all { measurer.measure(it, style, maxLines = 1).size.width <= share }
        if (fits) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                modifier = Modifier.fillMaxWidth().selectableGroup(),
            ) {
                options.forEachIndexed { index, option ->
                    OutlinedToggleButton(
                        checked = option == selected,
                        onCheckedChange = { if (option != selected) onSelect(option) },
                        enabled = enabled,
                        shapes =
                            when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    ) { Text(labels[index], maxLines = 1) }
                }
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().selectableGroup(),
            ) {
                options.forEachIndexed { index, option ->
                    OutlinedToggleButton(
                        checked = option == selected,
                        onCheckedChange = { if (option != selected) onSelect(option) },
                        enabled = enabled,
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    ) { Text(labels[index]) }
                }
            }
        }
    }
}

/** A toggle button's horizontal padding, with room for the pressed shape. */
private val LABEL_PADDING = 40.dp
