package app.mauri.cucuto.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R

/**
 * The app's one top bar (ADR 0027): a large flexible bar without a back arrow on tab roots, a medium flexible bar
 * with one on every screen pushed above them. Both collapse with [scrollBehavior] (`exitUntilCollapsed`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CucutoTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    backEnabled: Boolean = true,
) {
    // The title is the screen's heading: TalkBack users jump to it with the headings navigation.
    val subtitleSlot: (@Composable () -> Unit)? = subtitle?.let { { Text(it) } }
    if (onBack == null) {
        LargeFlexibleTopAppBar(
            title = { Text(title, Modifier.semantics { heading() }) },
            subtitle = subtitleSlot,
            actions = actions,
            scrollBehavior = scrollBehavior,
            modifier = modifier,
        )
    } else {
        MediumFlexibleTopAppBar(
            title = { Text(title, Modifier.semantics { heading() }) },
            subtitle = subtitleSlot,
            navigationIcon = { BackButton(onBack, enabled = backEnabled) },
            actions = actions,
            scrollBehavior = scrollBehavior,
            modifier = modifier,
        )
    }
}

/**
 * A screen pushed above a tab root (ADR 0027, ADR 0055): [CucutoTopBar] with a back arrow over [content], collapsing
 * as the content scrolls. [content] gets the scaffold's padding; lists apply it as content padding so they scroll
 * under the bars.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushedScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    backEnabled: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CucutoTopBar(
                title = title,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                subtitle = subtitle,
                actions = actions,
                backEnabled = backEnabled,
            )
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        content = content,
    )
}

/** The back arrow of pushed screens. */
@Composable
fun BackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onBack, modifier = modifier, enabled = enabled) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
    }
}

/** A row that opens another screen (Settings, Developer): icon, title, one line of explanation and a chevron. */
@Composable
fun LinkItem(
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick),
    )
}

/** A titled group of [LinkItem]s. */
@Composable
fun LinkGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
        )
        content()
    }
}
