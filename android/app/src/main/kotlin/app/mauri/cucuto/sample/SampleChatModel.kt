package app.mauri.cucuto.sample

import androidx.compose.runtime.Immutable
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.settings.SampleChatChoice
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.core.ringtone.variants.VariantRules
import app.mauri.cucuto.ui.ringtone.RingSound
import app.mauri.cucuto.ui.ringtone.RingtoneCharacterItem
import app.mauri.cucuto.ui.ringtone.RingtonePackItem

/**
 * Everything the sample chat shows (ADR 0075).
 *
 * @property packs Installed packs with the characters that can speak for messages (the picker's `voicePacks`).
 * @property ringtonePacks Installed packs with the characters that have ringtones.
 * @property choice What the sample chat remembers; a choice whose pack is gone counts as none.
 * @property speaking Whether the sample message is playing (the avatar bounces).
 * @property playFailed The last sample message or call could not play.
 */
@Immutable
data class SampleChatState(
    val loading: Boolean = true,
    val packs: List<VoicePack> = emptyList(),
    val ringtonePacks: List<RingtonePackItem> = emptyList(),
    val choice: SampleChatChoice = SampleChatChoice(),
    val speaking: Boolean = false,
    val playFailed: Boolean = false,
) {
    /** No character can speak yet: offer the demo packs. */
    val hasCharacters: Boolean get() = packs.any { it.characters.isNotEmpty() }

    /** The message character, if it's still installed. */
    val character: VoiceCharacter? get() = choice.voice?.let(::character)

    /** The voice's own ringtones, when it has some: what "Same as the voice" rings. */
    val voiceRingtone: RingtoneCharacterItem? get() = choice.voice?.let(::ringtone)

    /** The ringtone the user picked, if it's still installed. */
    val pickedRingtone: RingtoneCharacterItem? get() = choice.ringtone?.let(::ringtone)

    /** "Same as the voice" is the ringtone: nothing else picked (or it's gone) and the voice has ringtones. */
    val ringtoneFollowsVoice: Boolean get() = pickedRingtone == null && voiceRingtone != null

    /** What "Play a sample call" rings: the picked ringtone, else the voice's own, else none. */
    val ringtone: RingtoneCharacterItem? get() = pickedRingtone ?: voiceRingtone

    fun character(voice: ChatVoice): VoiceCharacter? =
        packs.firstOrNull { it.packId == voice.packId }?.characters?.firstOrNull { it.id == voice.characterId }

    fun ringtone(voice: ChatVoice): RingtoneCharacterItem? =
        ringtonePacks.firstOrNull { it.packId == voice.packId }?.characters?.firstOrNull { it.voice == voice }
}

/**
 * The version of [item]'s ringtone a call would ring at [context]: the pack's own rule (`when`), as a fresh contact
 * gets by default (ADR 0063). Null when its file isn't in the pack folder.
 */
fun sampleCallSound(
    item: RingtoneCharacterItem,
    context: RuleContext,
): RingSound? {
    if (item.variants.isEmpty()) return null
    val variant = VariantRules.followPack(item.variants.map { it.variant }, context)
    return item.variant(variant.file)?.sound()
}
