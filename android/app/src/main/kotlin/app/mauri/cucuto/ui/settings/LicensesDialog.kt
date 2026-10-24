package app.mauri.cucuto.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R

/** About › Licenses: the app (GPL), the font (OFL), the demo packs (CC0) and the libraries. */
@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.licenses_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                License(R.string.licenses_app, R.string.licenses_app_body, R.string.licenses_gpl_url)
                License(R.string.licenses_font, R.string.licenses_font_body, R.string.licenses_ofl_url)
                License(R.string.licenses_packs, R.string.licenses_packs_body, R.string.licenses_cc0_url)
                License(R.string.licenses_libraries, R.string.licenses_libraries_body, url = null)
                Text(
                    stringResource(R.string.licenses_your_packs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.licenses_close)) }
        },
    )
}

@Composable
private fun License(
    @StringRes name: Int,
    @StringRes body: Int,
    @StringRes url: Int?,
) {
    val uriHandler = LocalUriHandler.current
    Column {
        Text(stringResource(name), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        if (url != null) {
            val link = stringResource(url)
            TextButton(onClick = { openLink(uriHandler, link) }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.licenses_read))
            }
        }
    }
}
