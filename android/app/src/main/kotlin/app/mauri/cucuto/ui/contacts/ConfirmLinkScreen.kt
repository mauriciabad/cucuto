package app.mauri.cucuto.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.home.CandidateRow
import app.mauri.cucuto.home.HomeViewModel
import app.mauri.cucuto.home.LinkQuestion
import app.mauri.cucuto.home.name
import app.mauri.cucuto.home.previewHomeStateWithQuestion
import app.mauri.cucuto.navigation.parentViewModel
import app.mauri.cucuto.ui.components.BottomActionBar
import app.mauri.cucuto.ui.components.PushedScreen
import app.mauri.cucuto.ui.components.VoiceAvatar
import app.mauri.cucuto.ui.components.adaptiveMaxLines
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * Home → "Who is this?" (ADR 0065, ADR 0055): a recent sender whose name matches more than one chat added from
 * contacts, or one with a namesake. The user picks who it is, or "None of them". Works on Home's view model; closes
 * itself ([onDone]) once answered, or when the question is gone.
 */
@Composable
fun ConfirmLinkScreen(
    chat: ChatKey,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = parentViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val question = state.question(chat)
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(state.loading, question == null) { if (!state.loading && question == null) done() }
    if (question == null) return
    ConfirmLinkContent(
        question = question,
        onBack = onBack,
        onPick = { contactId ->
            viewModel.confirmLink(chat, contactId)
            onDone()
        },
        onNone = {
            viewModel.dismissRecent(chat)
            onDone()
        },
        modifier = modifier,
    )
}

/** Stateless "Who is this?", for previews and UI tests. */
@Composable
fun ConfirmLinkContent(
    question: LinkQuestion,
    onBack: () -> Unit,
    onPick: (Long) -> Unit,
    onNone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalFeedback.current
    val name = question.chat.chat.name()
    PushedScreen(
        title = stringResource(R.string.confirm_link_title),
        subtitle = name,
        onBack = onBack,
        modifier = modifier,
        bottomBar = {
            BottomActionBar {
                TextButton(
                    onClick = {
                        feedback.fire(Feedback.Tap)
                        onNone()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.confirm_link_none)) }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding =
                PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            item(key = "body") {
                Text(
                    stringResource(R.string.confirm_link_body, name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(question.candidates, key = { it.candidate.contactId }) { row ->
                CandidateItem(
                    row = row,
                    onClick = {
                        feedback.fire(Feedback.Confirm)
                        onPick(row.candidate.contactId)
                    },
                )
            }
        }
    }
}

@Composable
private fun CandidateItem(
    row: CandidateRow,
    onClick: () -> Unit,
) {
    val numbers = row.candidate.phoneNumbers.joinToString(", ") { formatNumber(it) }
    val voice =
        row.character?.let { stringResource(R.string.confirm_link_voice, it.name) }
            ?: stringResource(R.string.confirm_link_no_voice)
    ListItem(
        headlineContent = {
            Text(
                row.candidate.displayName ?: numbers,
                maxLines = adaptiveMaxLines(1),
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(listOf(numbers, voice).filter { it.isNotEmpty() }.joinToString("\n"), maxLines = adaptiveMaxLines(2))
        },
        leadingContent = {
            VoiceAvatar(character = row.character, name = row.candidate.displayName, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.confirm_link_pick), onClick = onClick),
    )
}

@Preview
@Composable
private fun ConfirmLinkPreview() {
    CucutoTheme(dynamicColor = false) {
        ConfirmLinkContent(previewHomeStateWithQuestion().questions.first(), {}, {}, {})
    }
}
