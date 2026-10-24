package app.mauri.cucuto.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R

/**
 * The manual backup options: a full backup (with the pack files), a password, and repository passwords (only with a
 * password). Confirming opens the system's "save as" picker.
 */
@Composable
internal fun ExportDialog(
    onDismiss: () -> Unit,
    onConfirm: (ExportForm) -> Unit,
) {
    var includePacks by rememberSaveable { mutableStateOf(false) }
    var encrypt by rememberSaveable { mutableStateOf(false) }
    var includeCredentials by rememberSaveable { mutableStateOf(false) }
    // Never in saved state: a password does not belong in a Bundle.
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val form = ExportForm(includePacks, encrypt, passphrase, confirmation, includeCredentials)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionRow(
                    title = stringResource(R.string.export_packs),
                    body = stringResource(R.string.export_packs_body),
                    checked = includePacks,
                    onChange = { includePacks = it },
                )
                OptionRow(
                    title = stringResource(R.string.export_encrypt),
                    body = stringResource(R.string.export_encrypt_body),
                    checked = encrypt,
                    onChange = { encrypt = it },
                )
                if (encrypt) {
                    val transformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation()
                    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.export_passphrase)) },
                        supportingText = {
                            Text(stringResource(R.string.export_passphrase_short, ExportForm.MIN_PASSPHRASE))
                        },
                        isError = passphrase.isNotEmpty() && form.passphraseTooShort,
                        singleLine = true,
                        visualTransformation = transformation,
                        keyboardOptions = keyboard,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text(stringResource(R.string.export_passphrase_confirm)) },
                        supportingText =
                            if (form.mismatch) {
                                { Text(stringResource(R.string.export_passphrase_mismatch)) }
                            } else {
                                null
                            },
                        isError = form.mismatch,
                        singleLine = true,
                        visualTransformation = transformation,
                        keyboardOptions = keyboard,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.toggleable(
                                showPassword,
                                role = Role.Checkbox,
                                onValueChange = { showPassword = it },
                            ),
                    ) {
                        Checkbox(checked = showPassword, onCheckedChange = null)
                        Text(stringResource(R.string.export_show_password), Modifier.padding(start = 8.dp))
                    }
                }
                OptionRow(
                    title = stringResource(R.string.export_credentials),
                    body = stringResource(R.string.export_credentials_body),
                    checked = encrypt && includeCredentials,
                    enabled = encrypt,
                    onChange = { includeCredentials = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(form) },
                enabled = form.canExport,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.export_choose_place)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.export_cancel)) }
        },
    )
}

@Composable
private fun OptionRow(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier.fillMaxWidth().toggleable(
                checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            ),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
