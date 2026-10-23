package app.mauri.cucuto.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.mauri.cucuto.R
import app.mauri.cucuto.ui.components.CuckooDoor
import app.mauri.cucuto.ui.components.ParticleBurst
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.status.CheckAction
import app.mauri.cucuto.ui.status.CheckRow
import app.mauri.cucuto.ui.status.CucutoLinks
import app.mauri.cucuto.ui.status.ModuleCheck
import app.mauri.cucuto.ui.status.SelfTestMessage
import app.mauri.cucuto.ui.status.SelfTestText
import app.mauri.cucuto.ui.status.StatusReport
import app.mauri.cucuto.ui.status.StatusViewModel
import app.mauri.cucuto.ui.status.moduleRow
import app.mauri.cucuto.ui.status.openUrl
import app.mauri.cucuto.ui.status.rememberCheckActionHandler
import app.mauri.cucuto.ui.status.scopeRow
import app.mauri.cucuto.ui.theme.CharacterAccentSeeds
import app.mauri.cucuto.ui.theme.CharacterTheme
import app.mauri.cucuto.ui.theme.CucutoTheme
import kotlinx.coroutines.delay

/** The five onboarding steps, in order. */
enum class OnboardingStep {
    Welcome,
    Requirements,
    Enable,
    Reboot,
    SelfTest,
    ;

    companion object {
        /**
         * Where a returning first-run user continues, for example after the reboot the flow asked for: null keeps
         * the welcome step.
         */
        fun resumeAt(module: ModuleCheck): OnboardingStep? =
            when (module) {
                is ModuleCheck.Active, is ModuleCheck.HooksMissing, ModuleCheck.AwaitingSelfTest -> SelfTest
                is ModuleCheck.NeedsReboot -> Reboot
                is ModuleCheck.FrameworkUnsupported, ModuleCheck.NotEnabled -> null
            }
    }
}

/**
 * First-run onboarding (#24): what Cucuto does, the root and LSPosed requirements with honest risks, enabling the
 * module, rebooting and posting the self-test. The module and scope checks are live, the same ones as on the status
 * card. [resume] (first run) jumps to the step that matches the module's state, so the flow continues after the
 * reboot. Skip and Done both finish it; Status can reopen it as the setup guide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    resume: Boolean = true,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selfTest by viewModel.selfTest.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
    var resumed by rememberSaveable { mutableStateOf(!resume) }
    val report = state.report
    LaunchedEffect(report != null) {
        if (report != null && !resumed) {
            resumed = true
            if (step == OnboardingStep.Welcome) OnboardingStep.resumeAt(report.module)?.let { step = it }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.postSelfTest() }
    val postSelfTest = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val onAction =
        rememberCheckActionHandler(report) { action ->
            if (action == CheckAction.PostSelfTest) postSelfTest()
        }

    val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = step.ordinal > 0,
        onBackCompleted = { step = OnboardingStep.entries[step.ordinal - 1] },
    )

    OnboardingContent(
        step = step,
        report = report,
        selfTest = selfTest,
        onStep = { step = it },
        onFinish = onFinish,
        onAction = onAction,
        onPostSelfTest = postSelfTest,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingContent(
    step: OnboardingStep,
    report: StatusReport?,
    selfTest: SelfTestMessage,
    onStep: (OnboardingStep) -> Unit,
    onFinish: () -> Unit,
    onAction: (CheckAction) -> Unit,
    onPostSelfTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = step == OnboardingStep.entries.last()
    val motion = MaterialTheme.motionScheme
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.onboarding_step, step.ordinal + 1, OnboardingStep.entries.size))
                },
                actions = {
                    if (!last) TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_skip)) }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LinearWavyProgressIndicator(
                    progress = { (step.ordinal + 1f) / OnboardingStep.entries.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (step.ordinal > 0) {
                        OutlinedButton(
                            onClick = { onStep(OnboardingStep.entries[step.ordinal - 1]) },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.onboarding_back))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { if (last) onFinish() else onStep(OnboardingStep.entries[step.ordinal + 1]) },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(
                            stringResource(
                                when {
                                    !last -> R.string.onboarding_next
                                    report?.module is ModuleCheck.Active -> R.string.onboarding_done
                                    else -> R.string.onboarding_later
                                },
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (
                    slideInHorizontally(motion.defaultSpatialSpec()) { direction * it / 4 } +
                        fadeIn(motion.defaultEffectsSpec())
                ) togetherWith (
                    slideOutHorizontally(motion.defaultSpatialSpec()) { -direction * it / 4 } +
                        fadeOut(motion.fastEffectsSpec())
                )
            },
            label = "step",
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) { current ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (current) {
                        OnboardingStep.Welcome -> WelcomeStep()
                        OnboardingStep.Requirements -> RequirementsStep()
                        OnboardingStep.Enable -> EnableStep(report, onAction)
                        OnboardingStep.Reboot -> RebootStep(report, onAction)
                        OnboardingStep.SelfTest -> SelfTestStep(report, selfTest, onAction, onPostSelfTest)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep() {
    val feedback = LocalFeedback.current
    var beat by remember { mutableStateOf<FeedbackBeat?>(null) }
    // The brand's signature greets the user once; tapping the door replays it.
    LaunchedEffect(Unit) {
        delay(WELCOME_DOOR_DELAY_MS)
        beat = feedback.fire(Feedback.DoorOpen)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CuckooDoor(
            beat = beat,
            contentDescription = stringResource(R.string.onboarding_door_description),
            modifier =
                Modifier
                    .fillMaxWidth(DOOR_WIDTH)
                    .clickable(onClickLabel = stringResource(R.string.onboarding_door_play)) {
                        beat = feedback.fire(Feedback.DoorOpen)
                    },
        )
    }
    StepTitle(R.string.onboarding_welcome_title)
    BodyText(R.string.onboarding_welcome_body)
    Point(Icons.Rounded.Favorite, R.string.onboarding_welcome_point_packs)
    Point(Icons.Rounded.Notifications, R.string.onboarding_welcome_point_android)
    Point(Icons.Rounded.Lock, R.string.onboarding_welcome_point_private)
}

@Composable
private fun ColumnScope.RequirementsStep() {
    val context = LocalContext.current
    StepTitle(R.string.onboarding_requirements_title)
    BodyText(R.string.onboarding_requirements_body)
    Point(Icons.Rounded.CheckCircle, R.string.onboarding_requirement_android)
    Point(Icons.Rounded.CheckCircle, R.string.onboarding_requirement_root)
    Point(Icons.Rounded.CheckCircle, R.string.onboarding_requirement_lsposed)
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.onboarding_risks_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            BodyText(R.string.onboarding_risks_body)
            Point(Icons.Rounded.Warning, R.string.onboarding_risk_bootloop)
            Point(Icons.Rounded.Warning, R.string.onboarding_risk_root)
            Point(Icons.Rounded.Warning, R.string.onboarding_risk_updates)
            Point(Icons.Rounded.Warning, R.string.onboarding_risk_privacy)
            Point(Icons.Rounded.Warning, R.string.onboarding_risk_recovery)
            Button(onClick = { context.openUrl(CucutoLinks.INSTALL_GUIDE) }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.onboarding_open_install_guide))
            }
        }
    }
}

@Composable
private fun ColumnScope.EnableStep(
    report: StatusReport?,
    onAction: (CheckAction) -> Unit,
) {
    StepTitle(R.string.onboarding_enable_title)
    BodyText(R.string.onboarding_enable_body)
    CheckRow(moduleRow(report?.module), onAction)
    CheckRow(scopeRow(report?.scope), onAction)
    Text(
        stringResource(R.string.onboarding_enable_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColumnScope.RebootStep(
    report: StatusReport?,
    onAction: (CheckAction) -> Unit,
) {
    StepTitle(R.string.onboarding_reboot_title)
    BodyText(R.string.onboarding_reboot_body)
    CheckRow(moduleRow(report?.module), onAction)
}

@Composable
private fun ColumnScope.SelfTestStep(
    report: StatusReport?,
    selfTest: SelfTestMessage,
    onAction: (CheckAction) -> Unit,
    onPostSelfTest: () -> Unit,
) {
    val feedback = LocalFeedback.current
    val active = report?.module is ModuleCheck.Active
    var beat by remember { mutableStateOf<FeedbackBeat?>(null) }
    var sawInactive by remember { mutableStateOf(false) }
    // The moment the self-test's report lands: success sound and haptic, the door pops open, particles fly.
    LaunchedEffect(active, report != null) {
        if (report == null) return@LaunchedEffect
        if (!active) {
            sawInactive = true
        } else if (sawInactive) {
            beat = feedback.fire(Feedback.Success)
        }
    }
    CharacterTheme(seed = if (active) CharacterAccentSeeds.Leaf else null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CuckooDoor(
                beat = beat,
                contentDescription =
                    stringResource(if (active) R.string.status_door_open else R.string.status_door_closed),
                modifier = Modifier.fillMaxWidth(DOOR_WIDTH),
            )
            ParticleBurst(beat, Modifier.size(PARTICLE_AREA))
        }
    }
    StepTitle(R.string.onboarding_test_title)
    BodyText(R.string.onboarding_test_body)
    Button(onClick = onPostSelfTest, shapes = ButtonDefaults.shapes()) {
        Text(stringResource(R.string.status_action_self_test))
    }
    SelfTestText(selfTest)
    CheckRow(moduleRow(report?.module), onAction)
    if (active) {
        Text(stringResource(R.string.onboarding_test_success), style = MaterialTheme.typography.titleMedium)
    } else if (selfTest == SelfTestMessage.Posted) {
        BodyText(R.string.onboarding_test_default_sound)
    }
}

@Composable
private fun StepTitle(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.headlineMediumEmphasized,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun BodyText(
    @StringRes text: Int,
) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Point(
    icon: ImageVector,
    @StringRes text: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private const val WELCOME_DOOR_DELAY_MS = 450L
private const val DOOR_WIDTH = 0.6f
private val PARTICLE_AREA = 320.dp

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun OnboardingRequirementsPreview() {
    CucutoTheme(dynamicColor = false) {
        OnboardingContent(
            step = OnboardingStep.Requirements,
            report = null,
            selfTest = SelfTestMessage.None,
            onStep = {},
            onFinish = {},
            onAction = {},
            onPostSelfTest = {},
        )
    }
}
