package app.mauri.cucuto.testing

import app.mauri.cucuto.core.data.chats.ChatHaptic
import app.mauri.cucuto.core.data.chats.ChatHapticOverrides
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.routing.HapticOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory overrides: chats with a voice and their override. Stores the JSON round trip, like Room does. */
class FakeChatHapticOverrides(
    voices: Map<ChatKey, ChatVoice> = emptyMap(),
) : ChatHapticOverrides {
    val stored = MutableStateFlow(voices.mapValues { (_, voice) -> voice to (null as String?) })
    var fail = false

    override fun observe(chat: ChatKey): Flow<ChatHaptic?> =
        stored.map { all ->
            all[chat]?.let { (voice, json) -> ChatHaptic(1, voice, HapticOverride.parse(json)) }
        }

    override suspend fun set(
        chat: ChatKey,
        override: HapticOverride,
    ): Boolean {
        if (fail) error("disk full")
        val (voice, _) = stored.value[chat] ?: return false
        stored.value = stored.value + (chat to (voice to override.toJson()))
        return true
    }

    fun overrideOf(chat: ChatKey): HapticOverride? = stored.value[chat]?.let { HapticOverride.parse(it.second) }
}
