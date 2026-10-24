package app.mauri.cucuto.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.ui.components.ChoiceButtonGroup
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.theme.CucutoTheme

/**
 * The group policy of one group chat (#20), for the contact detail screen: "the group" speaks with one voice (the
 * default, #56), or "each sender" speaks with their own voice, falling back to the group's.
 *
 * Stateless: show it only for groups ([app.mauri.cucuto.core.data.chats.Chat.isGroup]), pass
 * [app.mauri.cucuto.core.data.chats.Chat.groupPolicy], and store changes with
 * [app.mauri.cucuto.core.data.chats.ChatDirectory.setGroupPolicy] (which also works for a group that is not a
 * contact yet). The routing table republishes on its own.
 *
 * A Material 3 Expressive connected button group with single selection (m3.material.io, "Button groups": connected
 * button groups replace segmented buttons), each button exposed as a radio button.
 */
@Composable
fun GroupPolicySetting(
    policy: GroupPolicy,
    onPolicyChange: (GroupPolicy) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val feedback = LocalFeedback.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.chat_group_policy_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        ChoiceButtonGroup(
            options = GroupPolicy.entries,
            selected = policy,
            onSelect = { option ->
                feedback.fire(Feedback.Tap)
                onPolicyChange(option)
            },
            label = { stringResource(it.label()) },
            enabled = enabled,
        )
        AnimatedContent(targetState = policy, label = "group policy explanation") { shown ->
            Text(
                text = stringResource(shown.explanation()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun GroupPolicy.label(): Int =
    when (this) {
        GroupPolicy.GROUP -> R.string.chat_group_policy_group
        GroupPolicy.PER_SENDER -> R.string.chat_group_policy_per_sender
    }

private fun GroupPolicy.explanation(): Int =
    when (this) {
        GroupPolicy.GROUP -> R.string.chat_group_policy_group_body
        GroupPolicy.PER_SENDER -> R.string.chat_group_policy_per_sender_body
    }

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun GroupPolicySettingPreview() {
    var policy by remember { mutableStateOf(GroupPolicy.GROUP) }
    CucutoTheme {
        Surface {
            GroupPolicySetting(policy, { policy = it }, Modifier.padding(16.dp))
        }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun GroupPolicySettingPerSenderPreview() {
    CucutoTheme {
        Surface {
            GroupPolicySetting(GroupPolicy.PER_SENDER, {}, Modifier.padding(16.dp))
        }
    }
}
