package app.mauri.cucuto.ui.ringtone

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.mauri.cucuto.R

/** The contacts permissions phone ringtones need (ADR 0039). */
val CONTACTS_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

/**
 * Asks for the contacts permission the way the owner wants (#145): first a plain explanation of what Cucuto does with
 * contacts and where to read its code, then Android's dialog. If the user says no, a second dialog says why nothing
 * changed and offers Android's app settings. Returns the function that starts the flow; [onGrant] runs once both
 * permissions are granted.
 */
@Composable
fun rememberContactsPermissionFlow(onGrant: () -> Unit): () -> Unit =
    rememberExplainedPermission(
        permissions = CONTACTS_PERMISSIONS,
        title = R.string.ringtone_contacts_title,
        body = R.string.ringtone_contacts_body,
        explainDenial = true,
        onResult = { granted -> if (granted) onGrant() },
    )

/** Asks for `READ_PHONE_STATE` ("random per call" re-rolls when a call ends), with its explanation first. */
@Composable
fun rememberPhonePermissionFlow(onResult: (Boolean) -> Unit): () -> Unit =
    rememberExplainedPermission(
        permissions = arrayOf(Manifest.permission.READ_PHONE_STATE),
        title = R.string.ringtone_phone_title,
        body = R.string.ringtone_phone_body,
        explainDenial = false,
        onResult = onResult,
    )

@Composable
private fun rememberExplainedPermission(
    permissions: Array<String>,
    @StringRes title: Int,
    @StringRes body: Int,
    explainDenial: Boolean,
    onResult: (Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    var explaining by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val result by rememberUpdatedState(onResult)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
            val granted = permissions.all { answers[it] == true }
            if (!granted && explainDenial) denied = true
            result(granted)
        }

    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(body), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explaining = false
                        launcher.launch(permissions)
                    },
                ) { Text(stringResource(R.string.ringtone_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { explaining = false }) { Text(stringResource(R.string.ringtone_not_now)) }
            },
        )
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text(stringResource(R.string.ringtone_denied_title)) },
            text = { Text(stringResource(R.string.ringtone_denied_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        denied = false
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) { Text(stringResource(R.string.ringtone_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { denied = false }) { Text(stringResource(R.string.ringtone_close)) }
            },
        )
    }
    return {
        val granted =
            permissions.all {
                context.checkSelfPermission(it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        if (granted) result(true) else explaining = true
    }
}
