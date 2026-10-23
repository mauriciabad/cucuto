package app.mauri.cucuto.home

import androidx.compose.runtime.Immutable
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.data.voices.VoicePack
import java.text.Normalizer
import java.util.Locale

/**
 * One chat on the home screen with the character that speaks for it.
 *
 * @property character The assigned character, or null when the chat has no voice or its pack is gone.
 * @property packName Name of the character's pack.
 */
@Immutable
data class ChatRow(
    val chat: Chat,
    val character: VoiceCharacter?,
    val packName: String?,
) {
    val key: ChatKey get() = chat.key

    /** Assigned to a character that is no longer installed: the chat keeps WhatsApp's tone until reassigned. */
    val voiceMissing: Boolean get() = chat.voice != null && character == null
}

/** A pack the user can filter the list by. */
@Immutable
data class PackFilterOption(
    val packId: String,
    val name: String,
)

/**
 * Everything the home screen shows.
 *
 * @property recent The "recent senders" strip: chats without a voice that messaged in the last
 *   [HomeRules.RECENT_WINDOW_MS], newest first. Hidden while searching or filtering.
 * @property chats The chat list after search and pack filter.
 * @property unassigned Every chat without a voice, newest first (the "Assign a voice" sheet).
 * @property totalChats Chats before search and filter (tells "no chats yet" from "no matches").
 */
@Immutable
data class HomeState(
    val loading: Boolean = true,
    val recent: List<ChatRow> = emptyList(),
    val chats: List<ChatRow> = emptyList(),
    val unassigned: List<ChatRow> = emptyList(),
    val packs: List<PackFilterOption> = emptyList(),
    val packFilter: String? = null,
    val query: String = "",
    val totalChats: Int = 0,
) {
    val hasPacks: Boolean get() = packs.isNotEmpty()
    val filtering: Boolean get() = query.isNotBlank() || packFilter != null
    val showRecent: Boolean get() = !filtering && recent.isNotEmpty()
}

/** Defaults the owner can override (question issue linked from the PR). */
object HomeRules {
    /** How long an unassigned chat stays in the recent senders strip after its last notification: 7 days. */
    const val RECENT_WINDOW_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** At most this many chats in the strip. */
    const val RECENT_LIMIT: Int = 12
}

/**
 * Builds the home screen from the chats and the installed packs. Search matches chat names, character names and pack
 * names, ignoring case and accents; the pack filter keeps chats whose voice comes from that pack (a filter for a pack
 * that is no longer installed is ignored).
 */
fun buildHomeState(
    chats: List<Chat>,
    packs: List<VoicePack>,
    query: String,
    packFilter: String?,
    now: Long,
): HomeState {
    val characters = HashMap<Pair<String, String>, Pair<VoiceCharacter, String>>()
    for (pack in packs) {
        for (character in pack.characters) characters[pack.packId to character.id] = character to pack.name
    }
    val rows =
        chats.map { chat ->
            val match = chat.voice?.let { characters[it.packId to it.characterId] }
            ChatRow(chat, match?.first, match?.second)
        }
    val filter = packFilter?.takeIf { id -> packs.any { it.packId == id } }
    val needle = query.searchable()
    val visible =
        rows.filter { row ->
            (filter == null || row.chat.voice?.packId == filter) && (needle.isEmpty() || row.matches(needle))
        }
    val unassigned = rows.filter { it.chat.voice == null }
    val recent =
        unassigned
            .filter { row -> row.chat.lastSeenAt?.let { now - it <= HomeRules.RECENT_WINDOW_MS } == true }
            .take(HomeRules.RECENT_LIMIT)
    return HomeState(
        loading = false,
        recent = recent,
        chats = visible,
        unassigned = unassigned,
        packs = packs.map { PackFilterOption(it.packId, it.name) },
        packFilter = filter,
        query = query,
        totalChats = rows.size,
    )
}

private fun ChatRow.matches(needle: String): Boolean =
    listOfNotNull(chat.displayName, character?.name, packName).any { it.searchable().contains(needle) }

private val diacritics = Regex("\\p{Mn}+")

/** Lowercase without accents, so "jose" finds "José". */
internal fun String.searchable(): String =
    Normalizer
        .normalize(trim(), Normalizer.Form.NFD)
        .replace(diacritics, "")
        .lowercase(Locale.ROOT)
