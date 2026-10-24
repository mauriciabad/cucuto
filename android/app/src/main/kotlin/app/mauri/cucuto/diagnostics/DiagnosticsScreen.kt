package app.mauri.cucuto.diagnostics

import android.Manifest
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.db.DecisionLogEntity
import app.mauri.cucuto.core.data.routing.PublishState
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.provider.ReceivedStatus
import app.mauri.cucuto.xposed.events.ModuleStatus

/**
 * Diagnostics (issue #17): is the framework connected, is the routing table published, what did the module last
 * report, and the newest 50 decisions. "Post test notification" exercises the whole path end to end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.postTestNotification()
        }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InfoCard(
                    rows =
                        listOf(
                            stringResource(R.string.diag_framework) to
                                connectionText(state.connection, state.appUpdatedSinceBoot),
                            stringResource(R.string.diag_routing) to publishText(state.publish),
                        ) + moduleRows(state.moduleStatus, state.publish),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.diag_switch)) },
                    supportingContent = { Text(stringResource(R.string.diag_switch_body)) },
                    trailingContent = {
                        Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.clip(MaterialTheme.shapes.large),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.diag_post_test))
                    }
                    if (viewModel.testVoiceAvailable) {
                        OutlinedButton(onClick = viewModel::assignTestVoice, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.diag_test_voice))
                        }
                    }
                    Text(
                        text = messageText(message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { SectionHeader(R.string.diag_decisions_header) }
            if (state.decisions.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.diag_decisions_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.decisions, key = { it.id }) { DecisionRow(it) }
        }
    }
}

@Composable
private fun messageText(message: DiagnosticsMessage): String =
    when (message) {
        DiagnosticsMessage.None -> {
            stringResource(R.string.diag_post_test_hint)
        }

        DiagnosticsMessage.TestPosted -> {
            stringResource(R.string.diag_post_test_posted)
        }

        DiagnosticsMessage.NotificationsBlocked -> {
            stringResource(R.string.diag_post_test_blocked)
        }

        is DiagnosticsMessage.TestTooSoon -> {
            pluralStringResource(
                R.plurals.diag_too_soon,
                message.seconds,
                message.seconds,
            )
        }

        DiagnosticsMessage.NoSender -> {
            stringResource(R.string.diag_test_voice_no_sender)
        }

        is DiagnosticsMessage.VoiceAssigned -> {
            stringResource(R.string.diag_test_voice_assigned, message.label)
        }

        is DiagnosticsMessage.Failed -> {
            stringResource(R.string.diag_failed, message.reason)
        }
    }

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { (label, value) ->
                Column {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DecisionRow(entry: DecisionLogEntity) {
    val time = DateUtils.formatDateTime(LocalContext.current, entry.timestamp, DateUtils.FORMAT_SHOW_TIME)
    ListItem(
        overlineContent = { Text("$time · ${entry.packageName} · ${entry.hookMicros ?: 0} µs") },
        supportingContent = {
            Text(
                listOfNotNull(entry.conversationKey, entry.clipUri, entry.detail).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(entry.outcome.name)
    }
}

@Composable
private fun SectionHeader(
    @StringRes text: Int,
) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp).semantics { heading() },
    )
}

@Composable
private fun connectionText(
    connection: SinkConnection,
    appUpdatedSinceBoot: Boolean,
): String =
    when (connection) {
        SinkConnection.Disconnected -> {
            stringResource(
                if (appUpdatedSinceBoot) {
                    R.string.diag_framework_disconnected_update
                } else {
                    R.string.diag_framework_disconnected
                },
            )
        }

        is SinkConnection.Connected -> {
            stringResource(
                R.string.diag_framework_connected,
                connection.framework,
                connection.apiVersion,
            )
        }

        is SinkConnection.Unsupported -> {
            stringResource(R.string.diag_framework_unsupported, connection.framework)
        }
    }

@Composable
private fun publishText(state: PublishState): String =
    when (state) {
        PublishState.Idle -> {
            stringResource(R.string.diag_routing_idle)
        }

        is PublishState.Published -> {
            stringResource(
                R.string.diag_routing_published,
                state.generation,
                state.routes,
                state.voices,
            )
        }

        is PublishState.TooLarge -> {
            stringResource(R.string.diag_routing_too_large, state.encodedChars)
        }

        is PublishState.Failed -> {
            stringResource(R.string.diag_routing_failed, state.message)
        }

        is PublishState.Unsupported -> {
            stringResource(R.string.diag_framework_unsupported, state.framework)
        }
    }

@Composable
private fun moduleRows(
    received: ReceivedStatus?,
    publish: PublishState,
): List<Pair<String, String>> {
    val label = stringResource(R.string.diag_module)
    if (received == null) return listOf(label to stringResource(R.string.diag_module_never))
    val s = received.status
    val ago =
        DateUtils
            .getRelativeTimeSpanString(
                received.receivedAtMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
    return listOf(
        label to
            stringResource(
                R.string.diag_module_report,
                ago,
                s.hooks.joinToString().ifEmpty {
                    "—"
                },
                s.missingHooks.joinToString().ifEmpty { "—" },
            ),
        stringResource(R.string.diag_module_table) to moduleTableText(s, publish),
        stringResource(R.string.diag_module_timing) to
            stringResource(R.string.diag_module_timing_value, s.decisions, s.slowDecisions, s.maxHookMicros, s.dropped),
    )
}

/** The module's table, compared with the app's: the module reports a new table within seconds (ADR 0024). */
@Composable
private fun moduleTableText(
    s: ModuleStatus,
    publish: PublishState,
): String {
    val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    return when (moduleTableSync(s, publish, bootTime)) {
        ModuleTableSync.SAME -> {
            stringResource(R.string.diag_module_table_same, s.tableGeneration, s.tableRoutes)
        }

        ModuleTableSync.BEHIND -> {
            val latest = (publish as PublishState.Published).generation
            stringResource(R.string.diag_module_table_behind, s.tableGeneration, s.tableRoutes, latest)
        }

        ModuleTableSync.PREVIOUS_BOOT -> {
            stringResource(R.string.diag_module_table_previous_boot, s.tableGeneration, s.tableRoutes)
        }

        ModuleTableSync.UNKNOWN -> {
            stringResource(R.string.diag_module_table_value, s.tableGeneration, s.tableRoutes)
        }
    }
}
