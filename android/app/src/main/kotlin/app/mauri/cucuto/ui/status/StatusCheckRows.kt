package app.mauri.cucuto.ui.status

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.mauri.cucuto.R

/** A one-tap fix offered under a failing check. */
enum class CheckAction(
    @param:StringRes val label: Int,
) {
    PostSelfTest(R.string.status_action_self_test),
    OpenDiagnostics(R.string.status_action_diagnostics),
    OpenInstallGuide(R.string.status_action_install_guide),
    GetWhatsApp(R.string.status_action_get_whatsapp),
    OpenWhatsAppSettings(R.string.status_action_app_settings),
    ImportPack(R.string.status_action_import_pack),
    GetPacks(R.string.status_action_get_packs),
}

/** Everything a check row shows. */
data class CheckRowModel(
    @param:StringRes val title: Int,
    val icon: ImageVector,
    val outcome: CheckOutcome?,
    val supporting: String,
    val actions: List<CheckAction>,
)

/** The website pages the checks link to. */
object CucutoLinks {
    const val INSTALL_GUIDE = "https://cucuto.mauri.app/install/"
    const val PACKS = "https://cucuto.mauri.app/packs/"
    const val WHATSAPP_STORE = "https://play.google.com/store/apps/details?id=com.whatsapp"
    const val WHATSAPP = "com.whatsapp"
}

/** The fix each check offers; empty when an instruction is all there is (an LSPosed setting). */
fun ModuleCheck.actions(): List<CheckAction> =
    when (this) {
        is ModuleCheck.Active -> emptyList()

        is ModuleCheck.HooksMissing -> listOf(CheckAction.OpenDiagnostics)

        ModuleCheck.AwaitingSelfTest -> listOf(CheckAction.PostSelfTest)

        // The self-test also settles a wrong "not loaded" reading: a report from this boot makes the check pass.
        is ModuleCheck.NeedsReboot -> listOf(CheckAction.PostSelfTest)

        is ModuleCheck.FrameworkUnsupported -> listOf(CheckAction.OpenInstallGuide)

        ModuleCheck.NotEnabled -> listOf(CheckAction.OpenInstallGuide)
    }

fun WhatsAppCheck.actions(): List<CheckAction> =
    when (this) {
        is WhatsAppCheck.Found -> emptyList()
        is WhatsAppCheck.Disabled -> listOf(CheckAction.OpenWhatsAppSettings)
        WhatsAppCheck.Missing -> listOf(CheckAction.GetWhatsApp)
    }

fun PacksCheck.actions(): List<CheckAction> =
    when (this) {
        is PacksCheck.Installed -> emptyList()
        PacksCheck.None -> listOf(CheckAction.ImportPack, CheckAction.GetPacks)
    }

@Composable
fun moduleRow(check: ModuleCheck?): CheckRowModel =
    CheckRowModel(
        title = R.string.status_check_module,
        icon = Icons.Rounded.Notifications,
        outcome = check?.outcome,
        supporting =
            when (check) {
                null -> {
                    stringResource(R.string.status_checking)
                }

                is ModuleCheck.Active -> {
                    if (check.updatePending) {
                        stringResource(R.string.status_module_active_update)
                    } else {
                        stringResource(R.string.status_module_active, joined(check.hooks))
                    }
                }

                is ModuleCheck.HooksMissing -> {
                    stringResource(R.string.status_module_hooks_missing, joined(check.missing))
                }

                ModuleCheck.AwaitingSelfTest -> {
                    stringResource(R.string.status_module_awaiting)
                }

                is ModuleCheck.NeedsReboot -> {
                    stringResource(
                        if (check.afterUpdate) R.string.status_module_reboot_update else R.string.status_module_reboot,
                    )
                }

                is ModuleCheck.FrameworkUnsupported -> {
                    stringResource(R.string.status_module_unsupported, check.framework)
                }

                ModuleCheck.NotEnabled -> {
                    stringResource(R.string.status_module_not_enabled)
                }
            },
        actions = check?.actions().orEmpty(),
    )

@Composable
fun scopeRow(check: ScopeCheck?): CheckRowModel =
    CheckRowModel(
        title = R.string.status_check_scope,
        icon = Icons.Rounded.Lock,
        outcome = check?.outcome,
        supporting =
            when (check) {
                null -> {
                    stringResource(R.string.status_checking)
                }

                ScopeCheck.SystemOnly -> {
                    stringResource(R.string.status_scope_ok)
                }

                ScopeCheck.InferredFromModule -> {
                    stringResource(R.string.status_scope_inferred)
                }

                is ScopeCheck.MissingSystem -> {
                    if (check.others.isEmpty()) {
                        stringResource(R.string.status_scope_missing)
                    } else {
                        stringResource(R.string.status_scope_missing_others, joined(check.others))
                    }
                }

                is ScopeCheck.ExtraApps -> {
                    stringResource(R.string.status_scope_extra, joined(check.packages))
                }

                ScopeCheck.Unknown -> {
                    stringResource(R.string.status_scope_unknown)
                }
            },
        actions = emptyList(),
    )

@Composable
fun whatsAppRow(check: WhatsAppCheck?): CheckRowModel =
    CheckRowModel(
        title = R.string.status_check_whatsapp,
        icon = Icons.Rounded.Call,
        outcome = check?.outcome,
        supporting =
            when (check) {
                null -> {
                    stringResource(R.string.status_checking)
                }

                is WhatsAppCheck.Found -> {
                    stringResource(R.string.status_whatsapp_found, messengerNames(check.packages))
                }

                is WhatsAppCheck.Disabled -> {
                    stringResource(R.string.status_whatsapp_disabled, messengerNames(check.packages))
                }

                WhatsAppCheck.Missing -> {
                    stringResource(R.string.status_whatsapp_missing)
                }
            },
        actions = check?.actions().orEmpty(),
    )

@Composable
fun packsRow(check: PacksCheck?): CheckRowModel =
    CheckRowModel(
        title = R.string.status_check_packs,
        icon = Icons.Rounded.Favorite,
        outcome = check?.outcome,
        supporting =
            when (check) {
                null -> {
                    stringResource(R.string.status_checking)
                }

                is PacksCheck.Installed -> {
                    pluralStringResource(
                        R.plurals.status_packs_installed,
                        check.count,
                        check.count,
                    )
                }

                PacksCheck.None -> {
                    stringResource(R.string.status_packs_none)
                }
            },
        actions = check?.actions().orEmpty(),
    )

/** The four rows in card order; while loading, every row reads "Checking…". */
@Composable
fun checkRows(report: StatusReport?): List<CheckRowModel> =
    listOf(
        moduleRow(report?.module),
        scopeRow(report?.scope),
        whatsAppRow(report?.whatsApp),
        packsRow(report?.packs),
    )

@Composable
private fun joined(items: List<String>): String = items.joinToString(stringResource(R.string.status_list_separator))

@Composable
private fun messengerNames(packages: List<String>): String {
    val whatsApp = stringResource(R.string.status_whatsapp_name)
    val business = stringResource(R.string.status_whatsapp_business_name)
    return joined(
        packages.map {
            when (it) {
                "com.whatsapp" -> whatsApp
                "com.whatsapp.w4b" -> business
                else -> it
            }
        },
    )
}

/**
 * One check: its icon in a cookie, title, explanation, a status icon that also says the outcome to TalkBack (never
 * color alone), and the fixes as buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckRow(
    model: CheckRowModel,
    onAction: (CheckAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outcomeLabel =
        when (model.outcome) {
            CheckOutcome.Pass -> stringResource(R.string.status_outcome_pass)
            CheckOutcome.Fail -> stringResource(R.string.status_outcome_fail)
            CheckOutcome.Unknown -> stringResource(R.string.status_outcome_unknown)
            null -> stringResource(R.string.status_checking)
        }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(model.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(model.title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        model.supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutcomeIcon(
                    model.outcome,
                    Modifier.semantics { contentDescription = outcomeLabel },
                )
            }
            if (model.actions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 56.dp),
                ) {
                    model.actions.forEachIndexed { index, action ->
                        if (index == 0) {
                            FilledTonalButton(onClick = { onAction(action) }, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(action.label))
                            }
                        } else {
                            OutlinedButton(onClick = { onAction(action) }, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(action.label))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutcomeIcon(
    outcome: CheckOutcome?,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(outcome, modifier = modifier, label = "outcome") { current ->
        when (current) {
            CheckOutcome.Pass -> {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            }

            CheckOutcome.Fail -> {
                Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
            }

            CheckOutcome.Unknown -> {
                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            null -> {
                Box(Modifier.size(24.dp))
            }
        }
    }
}

/**
 * Runs the fixes that leave the app (website, Play Store, system settings). In-app ones ([CheckAction.PostSelfTest],
 * [CheckAction.OpenDiagnostics], [CheckAction.ImportPack]) belong to the screen and go to [inApp].
 */
@Composable
fun rememberCheckActionHandler(
    report: StatusReport?,
    inApp: (CheckAction) -> Unit,
): (CheckAction) -> Unit {
    val context = LocalContext.current
    val disabled = (report?.whatsApp as? WhatsAppCheck.Disabled)?.packages?.firstOrNull() ?: CucutoLinks.WHATSAPP
    return remember(context, inApp, disabled) {
        { action ->
            when (action) {
                CheckAction.OpenInstallGuide -> {
                    context.openUrl(CucutoLinks.INSTALL_GUIDE)
                }

                CheckAction.GetPacks -> {
                    context.openUrl(CucutoLinks.PACKS)
                }

                CheckAction.GetWhatsApp -> {
                    context.openUrl(CucutoLinks.WHATSAPP_STORE)
                }

                CheckAction.OpenWhatsAppSettings -> {
                    context.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:$disabled".toUri(),
                        ),
                    )
                }

                CheckAction.PostSelfTest, CheckAction.OpenDiagnostics, CheckAction.ImportPack -> {
                    inApp(action)
                }
            }
        }
    }
}

fun Context.openUrl(url: String) = launch(Intent(Intent.ACTION_VIEW, url.toUri()))

private fun Context.launch(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.status_action_unavailable, Toast.LENGTH_SHORT).show()
    }
}
