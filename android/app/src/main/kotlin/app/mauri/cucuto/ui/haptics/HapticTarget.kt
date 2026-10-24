package app.mauri.cucuto.ui.haptics

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.packspec.HapticEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the haptic editor edits (#32, #185). Serializable: it is part of the editor's navigation key, so it survives
 * process death with the back stack.
 */
@Serializable
sealed interface HapticTarget {
    /** A chat's custom vibration: the editor starts from its override and saving stores a new one (#33). */
    @Serializable
    data class Chat(
        val chat: ChatKey,
    ) : HapticTarget

    /**
     * A character of the pack creator (#47): the editor starts from [pattern] (the pack format's events, empty for a
     * new pattern) and saving hands the pattern back through [HapticEditorResults] instead of storing anything. The
     * creator writes it into the pack.
     *
     * @property characterKey The creator's key for the character (`DraftCharacter.key`).
     * @property name The character's name, for "Play with".
     * @property clip A clip of the character (an absolute path in the creator's draft) to preview the pattern with.
     */
    @Serializable
    data class CreatorCharacter(
        val characterKey: String,
        val name: String,
        val pattern: List<HapticEvent> = emptyList(),
        val clip: String? = null,
    ) : HapticTarget
}

/** A pattern the editor saved for a creator character ([HapticTarget.CreatorCharacter]). */
data class CreatorHapticResult(
    val characterKey: String,
    val haptic: Haptic.Custom,
)

/**
 * Hands patterns from the haptic editor back to the screen that opened it, for targets that store nothing
 * themselves (the creator's characters). A buffered channel: a result waits until the creator collects it, even when
 * the creator's view model is created only after the editor closes (process death).
 */
@Singleton
class HapticEditorResults
    @Inject
    constructor() {
        private val creator = Channel<CreatorHapticResult>(Channel.UNLIMITED)

        /** Results for the creator, each delivered once. */
        val creatorResults: Flow<CreatorHapticResult> = creator.receiveAsFlow()

        fun send(result: CreatorHapticResult) {
            creator.trySend(result)
        }
    }
