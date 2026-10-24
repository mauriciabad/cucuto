package app.mauri.cucuto.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.mauri.cucuto.R
import java.util.Locale

/**
 * Language (#107, ADR 0036): one row that opens Android's per-app language page, where Cucuto lists English and
 * Español (the LocaleConfig AGP generates). The system page is the documented place for the choice and keeps
 * "Same as the phone" there too; picking a language there recreates this screen in it.
 */
@Composable
internal fun LanguageSection() {
    val context = LocalContext.current
    // A language change recreates the activity with a new configuration, which refreshes the summary.
    val configuration = LocalConfiguration.current
    val appLocale = remember(configuration) { context.appLocale() }
    SettingsSection(R.string.settings_section_language) {
        SettingsLinkRow(
            title = stringResource(R.string.settings_language),
            supporting = appLocale?.nativeName() ?: stringResource(R.string.settings_language_system),
            onClick = { launchSafely { context.startActivity(appLanguageSettings(context)) } },
        )
    }
}

/** The language picked for Cucuto alone, or null when it follows the phone. */
private fun Context.appLocale(): Locale? =
    getSystemService(LocaleManager::class.java)
        ?.applicationLocales
        ?.takeUnless { it.isEmpty }
        ?.get(0)

/** "English", "Español": each language named in itself, as Android's language lists do. */
private fun Locale.nativeName(): String = getDisplayLanguage(this).replaceFirstChar { it.titlecase(this) }

private fun appLanguageSettings(context: Context) =
    Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
