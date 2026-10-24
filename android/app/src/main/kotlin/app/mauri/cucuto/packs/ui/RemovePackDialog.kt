package app.mauri.cucuto.packs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.packs.RemovalUi

/**
 * Confirms removing a pack. When contacts use its characters, it says so and asks what happens to them: keep their
 * character (WhatsApp's own tone until the pack is imported again, then it comes back; the default) or clear it.
 */
@Composable
fun RemovePackDialog(
    removal: RemovalUi,
    onClearAssignmentsChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pack = removal.pack
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.packs_remove_title, pack.name)) },
        text = {
            // Scrolls when large fonts make the choices taller than the dialog.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    pluralStringResource(
                        R.plurals.packs_remove_body,
                        pack.characters.size,
                        pack.characters.size,
                    ),
                )
                if (pack.contacts.isNotEmpty()) {
                    Text(
                        pluralStringResource(
                            R.plurals.packs_remove_contacts,
                            pack.contacts.size,
                            pack.contacts.size,
                            contactNames(pack),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Column(Modifier.selectableGroup()) {
                        Choice(
                            title = stringResource(R.string.packs_remove_keep),
                            body = stringResource(R.string.packs_remove_keep_body),
                            selected = !removal.clearAssignments,
                            enabled = !removal.removing,
                            onClick = { onClearAssignmentsChange(false) },
                        )
                        Choice(
                            title = stringResource(R.string.packs_remove_clear),
                            body = stringResource(R.string.packs_remove_clear_body),
                            selected = removal.clearAssignments,
                            enabled = !removal.removing,
                            onClick = { onClearAssignmentsChange(true) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !removal.removing,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.packs_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !removal.removing) {
                Text(stringResource(R.string.import_cancel))
            }
        },
    )
}

@Composable
private fun Choice(
    title: String,
    body: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
