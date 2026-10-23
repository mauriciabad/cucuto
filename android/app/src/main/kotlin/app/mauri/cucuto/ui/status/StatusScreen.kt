package app.mauri.cucuto.ui.status

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.BuildConfig
import app.mauri.cucuto.R
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.navigation.PreviewDestinations
import app.mauri.cucuto.navigation.screenInfo
import app.mauri.cucuto.ui.components.CuckooDoor
import app.mauri.cucuto.ui.components.CuckooDoorStill
import app.mauri.cucuto.ui.components.ParticleBurst
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.CharacterAccentSeeds
import app.mauri.cucuto.ui.theme.CharacterTheme
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * Status (#24): one card that turns green when everything works (module active in `system_server`, LSPosed scope,
 * WhatsApp, a pack), with a one-tap fix or a clear instruction under each failing check. Turning green fires
 * `Feedback.Success` and pops the cuckoo door open with a particle burst, all from the same beat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    onNavigate: (CucutoDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selfTest by viewModel.selfTest.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    var beat by remember { mutableStateOf<FeedbackBeat?>(null) }
    LaunchedEffect(viewModel, feedback) {
        viewModel.celebrations.collect { beat = feedback.fire(Feedback.Success) }
    }
    // Scope and installed apps change in other apps (LSPosed, the Play Store): re-check whenever Cucuto comes back.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.postSelfTest() }
    val onAction =
        rememberCheckActionHandler(state.report) { action ->
            when (action) {
                CheckAction.PostSelfTest -> requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                CheckAction.OpenDiagnostics -> onNavigate(CucutoDestination.Diagnostics)
                CheckAction.ImportPack -> onNavigate(CucutoDestination.Packs)
                else -> Unit
            }
        }
    StatusContent(
        report = state.report,
        beat = beat,
        selfTest = selfTest,
        onAction = onAction,
        onNavigate = onNavigate,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusContent(
    report: StatusReport?,
    beat: FeedbackBeat?,
    selfTest: SelfTestMessage,
    onAction: (CheckAction) -> Unit,
    onNavigate: (CucutoDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                subtitle = { Text(stringResource(R.string.status_title)) },
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
            item { StatusCard(report, beat, selfTest, onAction) }
            item { ScreenButtons(onNavigate) }
            item { SectionHeader(R.string.status_screens_header) }
            item { ScreenLinks(onNavigate) }
        }
    }
}

/** The card: brand colors while something is missing, cross-fading to the leaf character's green when ready. */
@Composable
fun StatusCard(
    report: StatusReport?,
    beat: FeedbackBeat?,
    selfTest: SelfTestMessage,
    onAction: (CheckAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = report?.allPassed == true
    CharacterTheme(seed = if (ready) CharacterAccentSeeds.Leaf else null) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            modifier = modifier.fillMaxWidth(),
        ) {
            Box {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusHero(report, ready, beat)
                    checkRows(report).forEach { CheckRow(it, onAction) }
                    SelfTestText(selfTest)
                }
                ParticleBurst(beat, Modifier.matchParentSize())
            }
        }
    }
}

@Composable
private fun StatusHero(
    report: StatusReport?,
    ready: Boolean,
    beat: FeedbackBeat?,
) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(112.dp), contentAlignment = Alignment.Center) {
            StatusDoor(report, ready, beat)
        }
        Spacer(Modifier.width(16.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            AnimatedContent(
                targetState = (report == null) to ready,
                label = "hero",
            ) { (loading, isReady) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text =
                            when {
                                loading -> stringResource(R.string.status_hero_loading)
                                isReady -> stringResource(R.string.status_ready_title)
                                else -> stringResource(R.string.status_hero_title)
                            },
                        style =
                            if (isReady) {
                                MaterialTheme.typography.headlineSmallEmphasized
                            } else {
                                MaterialTheme.typography.headlineSmall
                            },
                        modifier = Modifier.semantics { heading() },
                    )
                    if (report != null) {
                        Text(
                            text =
                                if (isReady) {
                                    stringResource(R.string.status_ready_body)
                                } else {
                                    pluralStringResource(
                                        R.plurals.status_hero_progress,
                                        report.passedCount,
                                        report.passedCount,
                                        CHECK_COUNT,
                                    )
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Loading indicator, then the cuckoo door: closed until ready, popping open on the celebration beat. */
@Composable
private fun StatusDoor(
    report: StatusReport?,
    ready: Boolean,
    beat: FeedbackBeat?,
) {
    when {
        report == null -> {
            LoadingIndicator(modifier = Modifier.size(64.dp))
        }

        ready && beat == null -> {
            // Already green when the screen opened: show the door open, without replaying the celebration.
            CuckooDoorStill(
                seconds = SETTLED_SECONDS,
                modifier = Modifier.fillMaxWidth().semanticsDescription(R.string.status_door_open),
            )
        }

        else -> {
            CuckooDoor(
                beat = beat.takeIf { ready },
                contentDescription =
                    stringResource(
                        if (ready) R.string.status_door_open else R.string.status_door_closed,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Modifier.semanticsDescription(
    @StringRes description: Int,
): Modifier {
    val text = stringResource(description)
    return semantics { contentDescription = text }
}

@Composable
fun SelfTestText(
    message: SelfTestMessage,
    modifier: Modifier = Modifier,
) {
    val text =
        when (message) {
            SelfTestMessage.None -> {
                return
            }

            SelfTestMessage.Posted -> {
                stringResource(R.string.status_self_test_posted)
            }

            SelfTestMessage.Blocked -> {
                stringResource(R.string.diag_post_test_blocked)
            }

            is SelfTestMessage.TooSoon -> {
                pluralStringResource(
                    R.plurals.diag_too_soon,
                    message.seconds,
                    message.seconds,
                )
            }

            is SelfTestMessage.Failed -> {
                stringResource(R.string.diag_failed, message.reason)
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenButtons(onNavigate: (CucutoDestination) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = { onNavigate(CucutoDestination.Diagnostics) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.screen_diagnostics))
        }
        OutlinedButton(onClick = { onNavigate(CucutoDestination.Onboarding) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.status_setup_guide))
        }
        if (BuildConfig.DEBUG) {
            OutlinedButton(onClick = { onNavigate(CucutoDestination.Gallery) }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.screen_gallery))
            }
        }
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
        modifier =
            Modifier
                .padding(start = 8.dp, top = 12.dp)
                .semantics { heading() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenLinks(onNavigate: (CucutoDestination) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PreviewDestinations.forEach { destination ->
            val info = destination.screenInfo() ?: return@forEach
            FilledTonalButton(
                onClick = { onNavigate(destination) },
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(info.icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(info.title))
            }
        }
    }
}

private const val CHECK_COUNT = 4

/** Long after the door's timeline ends: the settled open pose. */
private const val SETTLED_SECONDS = 3f

private val PreviewNotReady =
    StatusReport(
        module = ModuleCheck.AwaitingSelfTest,
        scope = ScopeCheck.SystemOnly,
        whatsApp = WhatsAppCheck.Found(listOf("com.whatsapp")),
        packs = PacksCheck.None,
    )

private val PreviewReady =
    StatusReport(
        module = ModuleCheck.Active(listOf("playSound", "playVibration"), updatePending = false),
        scope = ScopeCheck.SystemOnly,
        whatsApp = WhatsAppCheck.Found(listOf("com.whatsapp", "com.whatsapp.w4b")),
        packs = PacksCheck.Installed(2),
    )

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun StatusNotReadyPreview() {
    CucutoTheme(dynamicColor = false) {
        StatusContent(PreviewNotReady, beat = null, SelfTestMessage.None, onAction = {}, onNavigate = {})
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun StatusReadyPreview() {
    CucutoTheme(dynamicColor = false) {
        StatusContent(PreviewReady, beat = null, SelfTestMessage.None, onAction = {}, onNavigate = {})
    }
}
