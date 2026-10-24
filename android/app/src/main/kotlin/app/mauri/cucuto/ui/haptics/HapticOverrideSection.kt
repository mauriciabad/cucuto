package app.mauri.cucuto.ui.haptics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.ui.components.ChoiceButtonGroup
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * The per-contact vibration (#33) for [chat], as a self-contained card for the contact detail screen: keep the pack's
 * haptic, follow the voice, no vibration, or a custom pattern. "Custom" without a pattern, and "Edit pattern", open the
 * haptic editor ([onEditPattern], route `CucutoDestination.HapticEditor(chat)`). Changes are stored at once and reach
 * the module through the routing table.
 */
@Composable
fun HapticOverrideSection(
    chat: ChatKey,
    onEditPattern: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel =
        hiltViewModel<HapticOverrideViewModel, HapticOverrideViewModel.Factory>(
            key = "haptic-override:${chat.packageName}:${chat.conversationKey}",
            creationCallback = { factory -> factory.create(chat) },
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    LaunchedEffect(state.failed) {
        if (state.failed) {
            feedback.fire(Feedback.Error)
            viewModel.dismissError()
        }
    }
    HapticOverrideContent(
        state = state,
        onChoose = { choice ->
            feedback.fire(Feedback.Tap)
            if (viewModel.choose(choice) == HapticChoiceResult.OpenEditor) onEditPattern()
        },
        onPreview = viewModel::preview,
        onEditPattern = onEditPattern,
        modifier = modifier,
    )
}

/** Stateless per-contact vibration card, for previews and UI tests. */
@Composable
fun HapticOverrideContent(
    state: HapticOverrideState,
    onChoose: (HapticChoice) -> Unit,
    onPreview: () -> Unit,
    onEditPattern: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.haptic_override_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (state.loading) return@Column
            if (!state.hasVoice) {
                Text(
                    stringResource(R.string.haptic_override_no_voice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            ChoiceButtonGroup(
                options = HapticChoice.entries,
                selected = state.choice,
                onSelect = onChoose,
                label = { stringResource(it.label) },
            )
            Text(
                description(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val custom = state.custom
            if (state.choice == HapticChoice.Custom && custom != null) {
                HapticTimelineView(
                    events = custom.events,
                    selected = null,
                    windowMs = timelineWindowMs(HapticTimeline.durationOf(custom.events)),
                    interactive = false,
                    modifier = Modifier.fillMaxWidth().height(MINI_TIMELINE_HEIGHT).clearAndSetSemantics {},
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onPreview, enabled = state.character != null) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.haptic_override_preview))
                }
                OutlinedButton(onClick = onEditPattern) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(
                            if (state.custom ==
                                null
                            ) {
                                R.string.haptic_override_create
                            } else {
                                R.string.haptic_override_edit
                            },
                        ),
                    )
                }
            }
        }
    }
}

private val HapticChoice.label: Int
    get() =
        when (this) {
            HapticChoice.Pack -> R.string.haptic_override_pack
            HapticChoice.Auto -> R.string.haptic_override_auto
            HapticChoice.Off -> R.string.haptic_override_off
            HapticChoice.Custom -> R.string.haptic_override_custom
        }

@Composable
private fun description(state: HapticOverrideState): String =
    when (state.choice) {
        HapticChoice.Pack -> {
            when (state.character?.haptic) {
                Haptic.Auto -> stringResource(R.string.haptic_override_pack_auto)
                is Haptic.Custom -> stringResource(R.string.haptic_override_pack_custom)
                Haptic.Keep, Haptic.Off -> stringResource(R.string.haptic_override_pack_keep)
                null -> stringResource(R.string.haptic_override_pack_missing)
            }
        }

        HapticChoice.Auto -> {
            stringResource(R.string.haptic_override_auto_body)
        }

        HapticChoice.Off -> {
            stringResource(R.string.haptic_override_off_body)
        }

        HapticChoice.Custom -> {
            stringResource(R.string.haptic_override_custom_body)
        }
    }

private val MINI_TIMELINE_HEIGHT = 72.dp

@Preview(showBackground = true)
@Composable
private fun HapticOverridePreview() {
    CucutoTheme(dynamicColor = false) {
        HapticOverrideContent(
            state =
                HapticOverrideState(
                    loading = false,
                    hasVoice = true,
                    choice = HapticChoice.Custom,
                    custom =
                        Haptic.Custom(
                            listOf(
                                HapticEvent.Preset(0, HapticPreset.CLICK, 800),
                                HapticEvent.Preset(150, HapticPreset.TICK, 500),
                            ),
                        ),
                ),
            onChoose = {},
            onPreview = {},
            onEditPattern = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
