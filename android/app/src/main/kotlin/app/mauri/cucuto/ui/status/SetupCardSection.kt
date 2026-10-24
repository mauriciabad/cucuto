package app.mauri.cucuto.ui.status

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.ui.motion.SharedBounds
import kotlinx.coroutines.delay

/** What Home shows of the setup state. */
enum class SetupCardMode {
    /** Nothing: still loading, or everything works. */
    Hidden,

    /** The full status card: setup has never been all green yet. */
    Card,

    /** Setup was green once and something broke since: one line that opens Settings → Setup. */
    Attention,
}

/**
 * Home's setup card (ADR 0027): the full card until every check has been green once (its first celebration), then
 * nothing, or a one-line banner when something really breaks later. [everGreenAtStart] is whether setup had been green
 * before Home opened; [collapsed] turns true once the first celebration has played.
 *
 * Waiting for the self-test after a reboot is not "broken": the module only reports with its first alert (ADR 0015),
 * so it would otherwise nag after every reboot. Packs are left out too: Home has its own first-run prompt for them.
 */
fun setupCardMode(
    report: StatusReport?,
    everGreenAtStart: Boolean?,
    collapsed: Boolean,
): SetupCardMode =
    when {
        report == null || everGreenAtStart == null -> SetupCardMode.Hidden
        !everGreenAtStart && !collapsed -> SetupCardMode.Card
        report.needsAttention -> SetupCardMode.Attention
        else -> SetupCardMode.Hidden
    }

/** A check that fails for a reason the user must fix (see [setupCardMode]). */
val StatusReport.needsAttention: Boolean
    get() =
        (module.outcome == CheckOutcome.Fail && module != ModuleCheck.AwaitingSelfTest) ||
            scope.outcome == CheckOutcome.Fail ||
            whatsApp.outcome == CheckOutcome.Fail

/**
 * The setup state on top of Home. While setup has never been green it is the full status card with every fix; when
 * it turns green it celebrates (`Feedback.Success`, the door, the burst), lingers so the user sees it, and collapses
 * into Settings → Setup. Its bounds are shared with the Setup screen's card.
 */
@Composable
fun SetupCardSection(
    onNavigate: (CucutoDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    var celebrated by remember { mutableStateOf(false) }
    val setup = rememberSetupCardState(viewModel, onNavigate, onCelebrate = { celebrated = true })
    val everGreen by viewModel.everGreen.collectAsStateWithLifecycle()
    var everGreenAtStart by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(everGreen) {
        if (everGreenAtStart == null && everGreen != null) everGreenAtStart = everGreen
    }
    LaunchedEffect(celebrated) {
        if (celebrated) {
            delay(CELEBRATION_LINGER_MS)
            collapsed = true
        }
    }
    val mode = setupCardMode(setup.report, everGreenAtStart, collapsed)
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
        },
        label = "setup card",
        modifier = modifier,
    ) { current ->
        when (current) {
            SetupCardMode.Hidden -> {
                Unit
            }

            SetupCardMode.Card -> {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SharedBounds(SetupCardKey) {
                        StatusCard(setup.report, setup.beat, setup.selfTest, setup.onAction, compact = true)
                    }
                    SetupCardFooter(ready = setup.report?.allPassed == true, onNavigate = onNavigate)
                }
            }

            SetupCardMode.Attention -> {
                AttentionBanner(
                    onOpen = { onNavigate(CucutoDestination.Setup) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupCardFooter(
    ready: Boolean,
    onNavigate: (CucutoDestination) -> Unit,
) {
    if (ready) {
        Text(
            text = stringResource(R.string.home_setup_moves),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { onNavigate(CucutoDestination.Onboarding) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.status_setup_guide))
        }
        TextButton(onClick = { onNavigate(CucutoDestination.Setup) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.home_setup_details))
        }
    }
}

@Composable
private fun AttentionBanner(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SharedBounds(SetupCardKey, modifier) {
        Card(
            onClick = onOpen,
            shape = MaterialTheme.shapes.extraLarge,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(Icons.Rounded.Warning, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_attention_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_attention_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** How long the green card stays on Home after its celebration before it collapses. */
private const val CELEBRATION_LINGER_MS = 4_000L
