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
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import app.mauri.cucuto.R
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.ui.components.CuckooDoor
import app.mauri.cucuto.ui.components.CuckooDoorStill
import app.mauri.cucuto.ui.components.CucutoTopBar
import app.mauri.cucuto.ui.components.ParticleBurst
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.motion.SharedBounds
import app.mauri.cucuto.ui.theme.CharacterAccentSeeds
import app.mauri.cucuto.ui.theme.CharacterTheme
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * Settings → Setup (#24, #153): the status card that turns green when everything works (module active in
 * `system_server`, LSPosed scope, WhatsApp, a pack), with a one-tap fix or a clear instruction under each failing
 * check, plus the setup guide and Diagnostics. Until the card is green for the first time it also sits on top of Home
 * ([SetupCardSection]). Turning green fires `Feedback.Success` and pops the cuckoo door open with a particle burst,
 * all from the same beat.
 */
@Composable
fun SetupScreen(
    onNavigate: (CucutoDestination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val setup = rememberSetupCardState(viewModel, onNavigate)
    SetupContent(
        report = setup.report,
        beat = setup.beat,
        selfTest = setup.selfTest,
        onAction = setup.onAction,
        onNavigate = onNavigate,
        onBack = onBack,
        modifier = modifier,
    )
}

/** What the status card needs, wired to [viewModel]: live checks, the celebration beat and the one-tap fixes. */
@Stable
class SetupCardState(
    val report: StatusReport?,
    val beat: FeedbackBeat?,
    val selfTest: SelfTestMessage,
    val onAction: (CheckAction) -> Unit,
)

/**
 * Collects [viewModel] for a status card: re-checks on every resume (scope and installed apps change in other apps),
 * fires `Feedback.Success` when the card turns green, and handles the fixes (posting the self-test asks for the
 * notification permission first; Diagnostics and importing a pack navigate).
 */
@Composable
fun rememberSetupCardState(
    viewModel: StatusViewModel,
    onNavigate: (CucutoDestination) -> Unit,
    onCelebrate: () -> Unit = {},
): SetupCardState {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selfTest by viewModel.selfTest.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    var beat by remember { mutableStateOf<FeedbackBeat?>(null) }
    val celebrate by rememberUpdatedState(onCelebrate)
    LaunchedEffect(viewModel, feedback) {
        viewModel.celebrations.collect {
            beat = feedback.fire(Feedback.Success)
            celebrate()
        }
    }
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
    return SetupCardState(state.report, beat, selfTest, onAction)
}

/** Key of the status card in shared-element transitions: Home's setup card grows into the Setup screen's. */
data object SetupCardKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupContent(
    report: StatusReport?,
    beat: FeedbackBeat?,
    selfTest: SelfTestMessage,
    onAction: (CheckAction) -> Unit,
    onNavigate: (CucutoDestination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CucutoTopBar(
                title = stringResource(R.string.screen_setup),
                scrollBehavior = scrollBehavior,
                onBack = onBack,
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
            item { SharedBounds(SetupCardKey) { StatusCard(report, beat, selfTest, onAction) } }
            item { SetupButtons(onNavigate) }
        }
    }
}

/**
 * The card: brand colors while something is missing, cross-fading to the leaf character's green when ready.
 * [compact] (Home) lists only the checks that still need the user, and leaves packs to Home's own first-run prompt.
 */
@Composable
fun StatusCard(
    report: StatusReport?,
    beat: FeedbackBeat?,
    selfTest: SelfTestMessage,
    onAction: (CheckAction) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
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
                    val rows = checkRows(report)
                    val shown = if (compact) rows.dropLast(1).filter { it.outcome != CheckOutcome.Pass } else rows
                    shown.forEach { CheckRow(it, onAction) }
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
private fun SetupButtons(onNavigate: (CucutoDestination) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = { onNavigate(CucutoDestination.Onboarding) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.status_setup_guide))
        }
        OutlinedButton(onClick = { onNavigate(CucutoDestination.Diagnostics) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.screen_diagnostics))
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
private fun SetupNotReadyPreview() {
    CucutoTheme(dynamicColor = false) {
        SetupContent(PreviewNotReady, beat = null, SelfTestMessage.None, onAction = {}, onNavigate = {}, onBack = {})
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun SetupReadyPreview() {
    CucutoTheme(dynamicColor = false) {
        SetupContent(PreviewReady, beat = null, SelfTestMessage.None, onAction = {}, onNavigate = {}, onBack = {})
    }
}
