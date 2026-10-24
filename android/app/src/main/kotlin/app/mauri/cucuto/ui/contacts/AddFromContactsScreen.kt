package app.mauri.cucuto.ui.contacts

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.WhatsAppContact
import app.mauri.cucuto.ui.components.BottomActionBar
import app.mauri.cucuto.ui.components.CuckooDoor
import app.mauri.cucuto.ui.components.PushedScreen
import app.mauri.cucuto.ui.components.VoiceAvatar
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.settings.openLink
import app.mauri.cucuto.ui.theme.CucutoTheme
import java.util.Locale

/**
 * "Add a chat from contacts" (ADR 0065): with `READ_CONTACTS` granted, the WhatsApp entries of Android's contacts to
 * pick from. Without it, the explainer first, then Android's prompt, or Android's settings once Android no longer
 * shows its prompt (#337). [onDone] gets the chats saved.
 */
@Composable
fun AddFromContactsScreen(
    onBack: () -> Unit,
    onDone: (List<ChatKey>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddFromContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.load()
            } else {
                // After a refusal, Android shows no rationale only when it won't show its prompt again.
                val blocked =
                    activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) == false
                viewModel.denied(blocked)
            }
        }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state.done) { state.done?.let { done(it) } }
    val openSettings = {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Unit
    }
    AddFromContactsContent(
        state = state,
        onBack = onBack,
        onContinue = {
            when {
                !viewModel.needsPermission() -> viewModel.load()
                state.blocked -> openSettings()
                else -> permission.launch(Manifest.permission.READ_CONTACTS)
            }
        },
        onOpenSettings = openSettings,
        onQueryChange = viewModel::setQuery,
        onToggle = viewModel::toggle,
        onSave = viewModel::save,
        onConsumeFailure = viewModel::clearFailure,
        modifier = modifier,
    )
}

/** Stateless "Add a chat from contacts", for previews and UI tests. */
@Composable
fun AddFromContactsContent(
    state: AddFromContactsState,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
    onQueryChange: (String) -> Unit,
    onToggle: (EntryId) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    onConsumeFailure: () -> Unit = {},
) {
    val feedback = LocalFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val failedMessage = stringResource(R.string.contacts_failed)
    val consumeFailure by rememberUpdatedState(onConsumeFailure)
    LaunchedEffect(state.failed) {
        if (state.failed) {
            feedback.fire(Feedback.Error)
            snackbar.showSnackbar(failedMessage)
            consumeFailure()
        }
    }
    val listing = state.step == AddStep.List && state.total > 0
    PushedScreen(
        title = stringResource(R.string.contacts_add),
        onBack = onBack,
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            when {
                state.step == AddStep.Explainer -> {
                    BottomActionBar {
                        TextButton(onClick = onBack, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.contacts_not_now))
                        }
                        Button(
                            onClick = {
                                feedback.fire(Feedback.Tap)
                                onContinue()
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(
                                stringResource(
                                    if (state.blocked) R.string.contacts_open_settings else R.string.contacts_continue,
                                ),
                            )
                        }
                    }
                }

                listing -> {
                    BottomActionBar(Modifier.imePadding()) {
                        Button(
                            onClick = {
                                feedback.fire(Feedback.Confirm)
                                onSave()
                            },
                            enabled = state.selected > 0 && !state.saving,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(
                                if (state.selected > 0) {
                                    pluralStringResource(R.plurals.contacts_add_button, state.selected, state.selected)
                                } else {
                                    stringResource(R.string.contacts_add_none)
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        when (state.step) {
            AddStep.Explainer -> {
                Explainer(state.blocked, Modifier.padding(padding))
            }

            AddStep.Loading -> {
                Loading(Modifier.padding(padding))
            }

            AddStep.Denied -> {
                Denied(onOpenSettings, onContinue.takeUnless { state.blocked }, onBack, Modifier.padding(padding))
            }

            AddStep.List -> {
                if (state.total ==
                    0
                ) {
                    Empty(onBack, Modifier.padding(padding))
                } else {
                    ContactList(state, padding, onQueryChange, onToggle)
                }
            }
        }
    }
}

@Composable
private fun Explainer(
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val source = stringResource(R.string.settings_source_url)
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(stringResource(R.string.contacts_explainer_lead), style = MaterialTheme.typography.bodyLarge)
        if (blocked) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        stringResource(R.string.contacts_blocked_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(R.string.contacts_blocked_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        ExplainerPart(R.string.contacts_explainer_reads_title, R.string.contacts_explainer_reads_body)
        ExplainerPart(R.string.contacts_explainer_never_title, R.string.contacts_explainer_never_body)
        ExplainerPart(R.string.contacts_explainer_saves_title, R.string.contacts_explainer_saves_body)
        ExplainerPart(R.string.contacts_explainer_why_title, R.string.contacts_explainer_why_body)
        ExplainerPart(R.string.contacts_explainer_first_title, R.string.contacts_explainer_first_body)
        TextButton(onClick = { openLink(uriHandler, source) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.contacts_explainer_code))
        }
    }
}

@Composable
private fun ExplainerPart(
    title: Int,
    body: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val label = stringResource(R.string.contacts_loading)
        LoadingIndicator(Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun Denied(
    onOpenSettings: () -> Unit,
    onTryAgain: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Message(
        title = R.string.contacts_denied_title,
        body = R.string.contacts_denied_body,
        modifier = modifier,
    ) {
        Button(onClick = onOpenSettings, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.contacts_open_settings))
        }
        // Android won't show its prompt again once it is blocked: only its settings can grant the permission.
        if (onTryAgain != null) {
            TextButton(onClick = onTryAgain, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.contacts_try_again))
            }
        }
        TextButton(onClick = onBack, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.contacts_back)) }
    }
}

@Composable
private fun Empty(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Message(title = R.string.contacts_empty_title, body = R.string.contacts_empty_body, modifier = modifier) {
        Button(onClick = onBack, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.contacts_back)) }
    }
}

@Composable
private fun Message(
    title: Int,
    body: Int,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        CuckooDoor(beat = null, contentDescription = null, modifier = Modifier.size(120.dp))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        actions()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactList(
    state: AddFromContactsState,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onToggle: (EntryId) -> Unit,
) {
    val feedback = LocalFeedback.current
    val focus = LocalFocusManager.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
        contentPadding =
            PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
    ) {
        item(key = "hint") {
            Text(
                stringResource(R.string.contacts_list_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item(key = "search") {
            Surface(
                shape = SearchBarDefaults.inputFieldShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SearchBarDefaults.InputField(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    onSearch = { focus.clearFocus() },
                    expanded = false,
                    onExpandedChange = {},
                    placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.contacts_search_clear),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (state.rows.isEmpty()) {
            item(key = "no-matches") {
                Text(
                    stringResource(R.string.contacts_no_matches),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        items(state.rows, key = { "${it.entry.packageName}/${it.entry.lookupKey}" }) { row ->
            ContactItem(
                row = row,
                onToggle = {
                    feedback.fire(if (row.selected) Feedback.ToggleOff else Feedback.ToggleOn)
                    onToggle(row.id)
                },
            )
        }
    }
}

@Composable
private fun ContactItem(
    row: ContactRow,
    onToggle: () -> Unit,
) {
    val numbers = row.entry.phoneNumbers.joinToString(", ") { formatNumber(it) }
    val supporting =
        when {
            row.known -> stringResource(R.string.contacts_known)
            row.entry.packageName == ChatKey.WHATSAPP_BUSINESS -> stringResource(R.string.contacts_business, numbers)
            else -> numbers
        }
    ListItem(
        headlineContent = {
            Text(
                row.entry.displayName ?: numbers,
                maxLines = adaptiveMaxLines(1),
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(supporting, maxLines = adaptiveMaxLines(1), overflow = TextOverflow.Ellipsis) },
        leadingContent = { VoiceAvatar(character = null, name = row.entry.displayName, contentDescription = null) },
        trailingContent = { Checkbox(checked = row.selected, onCheckedChange = null, enabled = !row.known) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier =
            Modifier.toggleable(
                value = row.selected,
                enabled = !row.known,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
    )
}

/** An E.164 number as this phone writes numbers, or as it is when Android cannot format it. */
internal fun formatNumber(e164: String): String =
    PhoneNumberUtils.formatNumber(e164, Locale.getDefault().country) ?: e164

/** Made-up WhatsApp entries for previews and tests: no real names or numbers. */
internal fun previewContactsState(): AddFromContactsState {
    fun entry(
        key: String,
        name: String,
        number: String,
        packageName: String = ChatKey.WHATSAPP,
    ) = WhatsAppContact(key, name, packageName, listOf("$number@s.whatsapp.net"))
    val rows =
        listOf(
            ContactRow(entry("lk-1", "Ada", "34600000001"), known = true, selected = true),
            ContactRow(entry("lk-2", "Bruno", "34600000002"), known = false, selected = true),
            ContactRow(
                entry("lk-2", "Bruno", "34600000002", ChatKey.WHATSAPP_BUSINESS),
                known = false,
                selected = false,
            ),
            ContactRow(entry("lk-3", "Carla", "34600000003"), known = false, selected = false),
            ContactRow(entry("lk-4", "Dani", "34600000004"), known = false, selected = true),
            ContactRow(entry("lk-5", "Elena", "34600000005"), known = false, selected = false),
        )
    return AddFromContactsState(step = AddStep.List, rows = rows, total = rows.size, selected = 2)
}

@Preview
@Composable
private fun AddFromContactsExplainerPreview() {
    CucutoTheme(dynamicColor = false) {
        AddFromContactsContent(AddFromContactsState(), {}, {}, {}, {}, {}, {})
    }
}

@Preview
@Composable
private fun AddFromContactsListPreview() {
    CucutoTheme(dynamicColor = false) {
        AddFromContactsContent(previewContactsState(), {}, {}, {}, {}, {}, {})
    }
}
