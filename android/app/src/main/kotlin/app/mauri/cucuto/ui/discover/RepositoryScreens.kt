package app.mauri.cucuto.ui.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.repo.LinkError
import app.mauri.cucuto.core.repo.RepoError
import app.mauri.cucuto.core.repo.RepositoryLink
import app.mauri.cucuto.packs.plainWords
import app.mauri.cucuto.ui.components.BottomActionBar
import app.mauri.cucuto.ui.components.ChoiceButtonGroup
import app.mauri.cucuto.ui.components.PushedScreen

/**
 * Adds a repository (or a direct pack link), or signs in to a saved one: a screen on the Discover tab (ADR 0055). The
 * host is always shown, and anything but the project website gets a short warning before the user confirms (brief:
 * "Adding a repository from an unknown source shows a short warning"). Nothing is fetched until the user presses the
 * button.
 */
@Composable
internal fun RepositoryFormScreen(
    form: RepositoryForm,
    onChange: ((RepositoryForm) -> RepositoryForm) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signingIn = form.repositoryId != null
    val link = form.link
    PushedScreen(
        title =
            when {
                signingIn -> stringResource(R.string.discover_form_sign_in_title, form.host.orEmpty())
                form.fromLink -> stringResource(R.string.discover_form_link_title)
                else -> stringResource(R.string.discover_form_add_title)
            },
        onBack = onBack,
        modifier = modifier,
        bottomBar = {
            BottomActionBar(Modifier.imePadding()) {
                if (form.checking) {
                    val checking = stringResource(R.string.a11y_checking)
                    LoadingIndicator(Modifier.size(40.dp).semantics { contentDescription = checking })
                }
                TextButton(onClick = onBack) { Text(stringResource(R.string.import_cancel)) }
                Button(
                    onClick = onSubmit,
                    enabled = !form.checking && form.input.isNotBlank(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        stringResource(
                            when {
                                signingIn -> R.string.discover_sign_in
                                link is RepositoryLink.DirectPack -> R.string.discover_form_download
                                else -> R.string.discover_form_add
                            },
                        ),
                    )
                }
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
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!signingIn) {
                Text(
                    stringResource(
                        if (form.fromLink) R.string.discover_form_link_body else R.string.discover_form_add_body,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = form.input,
                    onValueChange = { text -> onChange { it.copy(input = text) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.discover_form_url)) },
                    placeholder = { Text(stringResource(R.string.discover_form_url_hint)) },
                    // A long link wraps instead of scrolling out of sight, also with large fonts.
                    maxLines = 4,
                    enabled = !form.checking,
                    isError = form.error is FormError.Link,
                    supportingText = linkSupportingText(form),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
            }
            form.host?.let { host ->
                HostCard(host = host, trusted = form.trusted, directPack = link is RepositoryLink.DirectPack)
            }
            SignInFields(form, onChange)
            (form.error as? FormError.Fetch)?.let { ErrorText(fetchMessage(it.error)) }
            if (form.error == FormError.InvalidCredentials) ErrorText(R.string.discover_form_invalid_credentials)
        }
    }
}

private fun linkSupportingText(form: RepositoryForm): (@Composable () -> Unit)? {
    val error = (form.error as? FormError.Link)?.error ?: return null
    return { Text(stringResource(linkMessage(error))) }
}

/** Where the link points, and a warning for anything but the project website. */
@Composable
private fun HostCard(
    host: String,
    trusted: Boolean,
    directPack: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (trusted) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
            ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (directPack) R.string.discover_form_host_pack else R.string.discover_form_host),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(host, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!trusted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.discover_form_unknown_source, host),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** No sign-in, username and password (HTTP Basic) or a token (bearer). Stored encrypted, sent only to the host. */
@Composable
private fun SignInFields(
    form: RepositoryForm,
    onChange: ((RepositoryForm) -> RepositoryForm) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.discover_form_sign_in), style = MaterialTheme.typography.titleSmall)
        val kinds = AuthKind.entries
        // Wraps instead of cutting "Username and password" short with large fonts.
        ChoiceButtonGroup(
            options = kinds,
            selected = form.auth,
            onSelect = { kind -> onChange { it.copy(auth = kind) } },
            label = { stringResource(authLabel(it)) },
            enabled = !form.checking,
        )
        when (form.auth) {
            AuthKind.NONE -> {
                Unit
            }

            AuthKind.BASIC -> {
                OutlinedTextField(
                    value = form.username,
                    onValueChange = { text -> onChange { it.copy(username = text) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.discover_form_username)) },
                    singleLine = true,
                    enabled = !form.checking,
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = { text -> onChange { it.copy(password = text) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.discover_form_password)) },
                    singleLine = true,
                    enabled = !form.checking,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }

            AuthKind.BEARER -> {
                OutlinedTextField(
                    value = form.token,
                    onValueChange = { text -> onChange { it.copy(token = text) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.discover_form_token)) },
                    singleLine = true,
                    enabled = !form.checking,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        }
        if (form.auth != AuthKind.NONE) {
            Text(
                stringResource(R.string.discover_form_sign_in_privacy, form.host.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorText(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Discover → Repositories (ADR 0055): turn them on or off, sign in, remove (all but the default one), add one. */
@Composable
internal fun RepositoriesScreen(
    repositories: List<SavedRepository>,
    actions: DiscoverActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    PushedScreen(
        title = stringResource(R.string.discover_repositories),
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = actions.onAdd) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.discover_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "list") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        repositories.forEachIndexed { index, repository ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            RepositoryRow(repository, actions)
                        }
                    }
                }
            }
            item(key = "add") {
                OutlinedButton(
                    onClick = actions.onAdd,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.discover_add), Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}

@Composable
private fun RepositoryRow(
    repository: SavedRepository,
    actions: DiscoverActions,
    modifier: Modifier = Modifier,
) {
    val host = hostOf(repository.url)
    val name = repository.name ?: host
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(
                if (repository.isDefault) stringResource(R.string.discover_repository_default, host) else host,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (repository.authType != RepositoryAuthType.NONE) {
                Text(
                    stringResource(R.string.discover_repository_signed_in),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { actions.onSignIn(repository) }) {
            Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.discover_sign_in_to, name))
        }
        if (!repository.isDefault) {
            IconButton(onClick = { actions.onRemove(repository) }) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.discover_remove, name))
            }
        }
        val switchLabel = stringResource(R.string.discover_repository_show, name)
        Switch(
            checked = repository.enabled,
            onCheckedChange = { actions.onSetEnabled(repository, it) },
            modifier = Modifier.semantics { contentDescription = switchLabel },
        )
    }
}

@StringRes
private fun authLabel(kind: AuthKind): Int =
    when (kind) {
        AuthKind.NONE -> R.string.discover_auth_none
        AuthKind.BASIC -> R.string.discover_auth_basic
        AuthKind.BEARER -> R.string.discover_auth_bearer
    }

@StringRes
internal fun linkMessage(error: LinkError): Int =
    when (error) {
        LinkError.NOT_A_URL -> R.string.discover_link_not_url
        LinkError.NOT_HTTPS -> R.string.discover_link_not_https
        LinkError.CREDENTIALS_IN_URL -> R.string.discover_link_credentials
        LinkError.NOT_A_DEEP_LINK, LinkError.MISSING_URL -> R.string.discover_bad_link
        LinkError.DRIVE_FOLDER -> R.string.discover_link_drive_folder
    }

/** A failed check while adding: a 401 or 403 asks for the sign-in below. */
@StringRes
private fun fetchMessage(error: RepoError): Int =
    if (error is RepoError.Unauthorized) R.string.discover_form_unauthorized else plainWords(error)
