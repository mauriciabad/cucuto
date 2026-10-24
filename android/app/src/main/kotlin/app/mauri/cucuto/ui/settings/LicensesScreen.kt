package app.mauri.cucuto.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.ui.components.PushedScreen
import app.mauri.cucuto.ui.theme.CucutoTheme

/** Settings → About → Licenses (ADR 0028): the app (GPL), the font (OFL), the demo packs (CC0) and the libraries. */
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PushedScreen(title = stringResource(R.string.licenses_title), onBack = onBack, modifier = modifier) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "app") { License(R.string.licenses_app, R.string.licenses_app_body, R.string.licenses_gpl_url) }
            item(key = "font") {
                License(R.string.licenses_font, R.string.licenses_font_body, R.string.licenses_ofl_url)
            }
            item(key = "packs") {
                License(R.string.licenses_packs, R.string.licenses_packs_body, R.string.licenses_cc0_url)
            }
            item(key = "libraries") {
                License(R.string.licenses_libraries, R.string.licenses_libraries_body, url = null)
            }
            item(key = "yours") {
                Text(
                    stringResource(R.string.licenses_your_packs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun License(
    @StringRes name: Int,
    @StringRes body: Int,
    @StringRes url: Int?,
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (url == null) 16.dp else 4.dp)) {
            Text(
                stringResource(name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            if (url != null) {
                val link = stringResource(url)
                TextButton(onClick = { openLink(uriHandler, link) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.licenses_read))
                }
            }
        }
    }
}

@Preview
@Composable
private fun LicensesPreview() {
    CucutoTheme(dynamicColor = false) { LicensesScreen(onBack = {}) }
}
