package app.mauri.cucuto.testing

import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatDirectory
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.voice.VoicePreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Made-up test data: no real names, keys or packs. */
object TestData {
    const val WA = "com.whatsapp"
    const val CRITTERS = "app.mauri.demo.critters"
    const val KITCHEN = "app.mauri.demo.kitchen"
    const val NOW = 1_761_000_000_000L

    val ADA = ChatKey(WA, "100000000000001@lid")
    val BEA = ChatKey(WA, "100000000000002@lid")
    val CLUB = ChatKey(WA, "120300000000001@g.us")

    fun character(
        id: String,
        name: String,
        packId: String = CRITTERS,
        clips: Int = 2,
    ) = VoiceCharacter(
        packId = packId,
        id = id,
        name = name,
        sprite = null,
        spriteAnimated = null,
        seedColor = null,
        clips = List(clips) { File("/packs/$packId/1.0.0/characters/$id/clips/$it.ogg") },
        haptic = Haptic.Keep,
    )

    val frog = character("frog", "Bleep the frog")
    val owl = character("owl", "Hoot the owl")
    val kettle = character("kettle", "Whistle the kettle", packId = KITCHEN)

    val packs =
        listOf(
            VoicePack(CRITTERS, "Chiptune critters", listOf(frog, owl)),
            VoicePack(KITCHEN, "Kitchen orchestra", listOf(kettle)),
        )

    fun chat(
        key: ChatKey,
        name: String?,
        voice: ChatVoice? = null,
        lastSeenAt: Long? = NOW - 60_000,
        contactId: Long? = if (voice != null) 1 else null,
    ) = Chat(
        key = key,
        contactId = contactId,
        displayName = name,
        isGroup = key.isGroupKey,
        voice = voice,
        lastSeenAt = lastSeenAt,
        messageCount = 1,
    )
}

class FakeChatDirectory(
    chats: List<Chat> = emptyList(),
) : ChatDirectory {
    val chats = MutableStateFlow(chats)
    val assigned = mutableListOf<Triple<ChatKey, String, String>>()
    val unassigned = mutableListOf<ChatKey>()
    val dismissed = mutableListOf<ChatKey>()
    var failAssign = false

    override fun observeChats(): Flow<List<Chat>> = chats

    override suspend fun assign(
        chat: ChatKey,
        packId: String,
        characterId: String,
    ): Long {
        if (failAssign) error("disk full")
        assigned += Triple(chat, packId, characterId)
        setVoice(chat, ChatVoice(packId, characterId))
        return 1
    }

    override suspend fun unassign(chat: ChatKey) {
        unassigned += chat
        setVoice(chat, null)
    }

    private fun setVoice(
        chat: ChatKey,
        voice: ChatVoice?,
    ) = chats.update { list -> list.map { if (it.key == chat) it.copy(voice = voice) else it } }

    override suspend fun dismissRecent(chat: ChatKey) {
        dismissed += chat
    }
}

class FakeCharacterDirectory(
    packs: List<VoicePack> = TestData.packs,
) : CharacterDirectory {
    val packs = MutableStateFlow(packs)

    override fun observePacks(): Flow<List<VoicePack>> = packs
}

class FakeVoicePreview(
    var durationMs: Long? = 500,
) : VoicePreview {
    data class Play(
        val characterId: String,
        val withHaptic: Boolean,
        val atNanos: Long?,
    )

    val plays = mutableListOf<Play>()
    var stops = 0

    override suspend fun play(
        character: VoiceCharacter,
        withHaptic: Boolean,
        atNanos: Long?,
    ): Long? {
        plays += Play(character.id, withHaptic, atNanos)
        return durationMs
    }

    /** Clips played with a given haptic (haptic editor and overrides): character ID and haptic. */
    val hapticPlays = mutableListOf<Pair<String, Haptic>>()

    /** Patterns played alone. */
    val vibrations = mutableListOf<Haptic.Custom>()

    override suspend fun play(
        character: VoiceCharacter,
        haptic: Haptic,
    ): Long? {
        hapticPlays += character.id to haptic
        return durationMs
    }

    override fun vibrate(haptic: Haptic.Custom): Boolean {
        vibrations += haptic
        return true
    }

    override fun stop() {
        stops++
    }
}
