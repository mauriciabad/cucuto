package app.mauri.cucuto.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.navigation.parentViewModel
import app.mauri.cucuto.ui.components.PushedScreen
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * Home → Assign a voice (ADR 0055): every chat without a voice, newest first, from Home's own view model. Picking one
 * opens the character picker for it ([onPick] hands over to the picker, so back from there returns to Home).
 */
@Composable
fun AssignVoiceScreen(
    onBack: () -> Unit,
    onPick: (ChatKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = parentViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AssignVoiceContent(state = state, onBack = onBack, onPick = onPick, modifier = modifier)
}

/** Stateless: the list of [HomeState.unassigned] chats. */
@Composable
internal fun AssignVoiceContent(
    state: HomeState,
    onBack: () -> Unit,
    onPick: (ChatKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    PushedScreen(title = stringResource(R.string.home_sheet_title), onBack = onBack, modifier = modifier) { padding ->
        when {
            state.loading -> {
                val label = stringResource(R.string.home_loading)
                LoadingIndicator(Modifier.padding(padding).padding(48.dp).semantics { contentDescription = label })
            }

            state.unassigned.isEmpty() -> {
                Text(
                    text = stringResource(R.string.home_sheet_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(padding).padding(24.dp),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                    contentPadding =
                        PaddingValues(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding() + 24.dp,
                        ),
                ) {
                    items(state.unassigned, key = { "assign/${it.key.packageName}/${it.key.conversationKey}" }) { row ->
                        ChatListItem(
                            row = row,
                            shared = false,
                            onClick = {
                                feedback.fire(Feedback.Tap)
                                onPick(row.key)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun AssignVoicePreview() {
    CucutoTheme(dynamicColor = false) { AssignVoiceContent(state = previewHomeState(), onBack = {}, onPick = {}) }
}
