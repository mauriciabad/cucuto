package app.mauri.cucuto.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.backup.BackupFileNames
import app.mauri.cucuto.navigation.parentViewModel
import app.mauri.cucuto.ui.components.BottomActionBar
import app.mauri.cucuto.ui.components.PushedScreen
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * Settings → Back up to a file (ADR 0028): a full backup (with the pack files), a password, and repository passwords
 * (only with a password). "Choose where to save" opens the system's "save as" picker; once a place is picked the
 * screen closes and Settings shows the export's progress and result. Cancelling the picker stays here. Works on
 * Settings' view model ([parentViewModel]), which holds the password only until the export.
 */
@Composable
fun BackupExportScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = parentViewModel(),
) {
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFileNames.MIME_TYPE)) { uri ->
            viewModel.exportTo(uri?.toString())
            if (uri != null) onDone()
        }
    BackupExportContent(
        onBack = onBack,
        onConfirm = { form ->
            if (viewModel.prepareExport(form)) launchSafely { createDocument.launch(viewModel.suggestedFileName()) }
        },
        modifier = modifier,
    )
}

/** The backup options form; [onConfirm] gets what the user chose. */
@Composable
internal fun BackupExportContent(
    onBack: () -> Unit,
    onConfirm: (ExportForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    var includePacks by rememberSaveable { mutableStateOf(false) }
    var encrypt by rememberSaveable { mutableStateOf(false) }
    var includeCredentials by rememberSaveable { mutableStateOf(false) }
    // Never in saved state: a password does not belong in a Bundle.
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val form = ExportForm(includePacks, encrypt, passphrase, confirmation, includeCredentials)

    PushedScreen(
        title = stringResource(R.string.export_title),
        onBack = onBack,
        modifier = modifier,
        bottomBar = {
            BottomActionBar(Modifier.imePadding()) {
                Button(
                    onClick = { onConfirm(form) },
                    enabled = form.canExport,
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.export_choose_place)) }
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OptionGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.export_packs),
                    supporting = stringResource(R.string.export_packs_body),
                    checked = includePacks,
                    onCheckedChange = { includePacks = it },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.export_encrypt),
                    supporting = stringResource(R.string.export_encrypt_body),
                    checked = encrypt,
                    onCheckedChange = { encrypt = it },
                )
            }
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
            OptionGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.export_credentials),
                    supporting = stringResource(R.string.export_credentials_body),
                    checked = encrypt && includeCredentials,
                    enabled = encrypt,
                    onCheckedChange = { includeCredentials = it },
                )
            }
        }
    }
}

@Composable
private fun OptionGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Preview
@Composable
private fun BackupExportPreview() {
    CucutoTheme(dynamicColor = false) { BackupExportContent(onBack = {}, onConfirm = {}) }
}
