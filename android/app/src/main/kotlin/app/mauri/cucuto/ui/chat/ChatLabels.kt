package app.mauri.cucuto.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey

/**
 * A chat row's supporting line: [detail] (its voice, or "default tone"), prefixed with what kind of chat it is:
 * "Group", "Business", "Signal" (or "Molly"), or "WhatsApp and Signal" for a person seen in both (ADR 0081). WhatsApp
 * and WhatsApp Business keep separate keys and voices (#21), so the same person can appear twice; the prefix tells the
 * rows apart. A WhatsApp chat has no app prefix, as before.
 */
@Composable
fun chatSupportingText(
    chat: Chat,
    detail: String,
): String {
    val signal = chat.signalApp?.let { messengerName(it) }
    return when {
        signal != null && chat.onWhatsApp -> stringResource(R.string.chat_kind_whatsapp_and_app, signal, detail)
        signal != null && chat.isGroup -> stringResource(R.string.chat_kind_app_group, signal, detail)
        signal != null -> stringResource(R.string.chat_kind_app, signal, detail)
        chat.isBusiness && chat.isGroup -> stringResource(R.string.chat_kind_business_group, detail)
        chat.isBusiness -> stringResource(R.string.chat_kind_business, detail)
        chat.isGroup -> stringResource(R.string.home_chat_group, detail)
        else -> detail
    }
}

/**
 * The contact detail's subtitle: "WhatsApp chat", "WhatsApp Business group", "Signal chat", "WhatsApp and Signal
 * chat", and so on. [chat] is null while it loads; [chatKey] and [isGroup] then decide.
 */
@Composable
fun chatKindLabel(
    chat: Chat?,
    chatKey: ChatKey,
    isGroup: Boolean,
): String {
    val signal = (chat?.signalApp ?: chatKey.packageName.takeIf(::isSignalApp))?.let { messengerName(it) }
    val business = chatKey.packageName == ChatKey.WHATSAPP_BUSINESS
    return when {
        signal != null && chat?.onWhatsApp == true -> {
            stringResource(R.string.contact_subtitle_whatsapp_and_signal, signal)
        }

        signal != null && isGroup -> {
            stringResource(R.string.contact_subtitle_signal_group, signal)
        }

        signal != null -> {
            stringResource(R.string.contact_subtitle_signal_chat, signal)
        }

        business && isGroup -> {
            stringResource(R.string.contact_subtitle_business_group)
        }

        business -> {
            stringResource(R.string.contact_subtitle_business_chat)
        }

        isGroup -> {
            stringResource(R.string.contact_subtitle_group)
        }

        else -> {
            stringResource(R.string.contact_subtitle_chat)
        }
    }
}

/** A messenger's name as people know it; the package itself for anything unknown. */
@Composable
fun messengerName(packageName: String): String =
    when (packageName) {
        ChatKey.WHATSAPP -> stringResource(R.string.status_whatsapp_name)
        ChatKey.WHATSAPP_BUSINESS -> stringResource(R.string.status_whatsapp_business_name)
        ChatKey.SIGNAL -> stringResource(R.string.status_signal_name)
        ChatKey.MOLLY -> stringResource(R.string.status_molly_name)
        else -> packageName
    }

private fun isSignalApp(packageName: String): Boolean = packageName == ChatKey.SIGNAL || packageName == ChatKey.MOLLY
