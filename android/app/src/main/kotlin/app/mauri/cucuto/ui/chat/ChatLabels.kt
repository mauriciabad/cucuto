package app.mauri.cucuto.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.Chat

/**
 * A chat row's supporting line: [detail] (its voice, or "WhatsApp's tone"), prefixed with what kind of chat it is,
 * "Group" and/or "Business". WhatsApp and WhatsApp Business keep separate keys and voices (#21), so the same person
 * can appear twice; the prefix tells the rows apart.
 */
@Composable
fun chatSupportingText(
    chat: Chat,
    detail: String,
): String =
    when {
        chat.isBusiness && chat.isGroup -> stringResource(R.string.chat_kind_business_group, detail)
        chat.isBusiness -> stringResource(R.string.chat_kind_business, detail)
        chat.isGroup -> stringResource(R.string.home_chat_group, detail)
        else -> detail
    }
