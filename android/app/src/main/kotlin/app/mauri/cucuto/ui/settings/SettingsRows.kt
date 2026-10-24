package app.mauri.cucuto.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback

/** A titled group of rows on one rounded surface (Material 3 lists, grouped). */
@Composable
internal fun SettingsSection(
    @StringRes title: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp).semantics { heading() },
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(content = content)
        }
    }
}

private val rowColors @Composable get() =
    ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )

/** A row that opens something: another screen, a dialog or a link. */
@Composable
internal fun SettingsLinkRow(
    title: String,
    supporting: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArrow: Boolean = true,
) {
    val feedback = LocalFeedback.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent =
            if (showArrow) {
                { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) }
            } else {
                null
            },
        colors = rowColors,
        modifier =
            modifier.clickable(role = Role.Button) {
                feedback.fire(Feedback.Tap)
                onClick()
            },
    )
}

/** A row with a switch; the whole row toggles it. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val feedback = LocalFeedback.current
    val toggle: (Boolean) -> Unit = { on ->
        feedback.fire(if (on) Feedback.ToggleOn else Feedback.ToggleOff)
        onCheckedChange(on)
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = rowColors,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = toggle),
    )
}

/** A row that only shows a value. */
@Composable
internal fun SettingsInfoRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        colors = rowColors,
        modifier = modifier,
    )
}
