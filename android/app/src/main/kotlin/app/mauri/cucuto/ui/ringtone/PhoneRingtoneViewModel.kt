package app.mauri.cucuto.ui.ringtone

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.ChatDirectory
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.db.RingtoneRules
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.ringtone.ContactPhoneRingtone
import app.mauri.cucuto.core.ringtone.PhoneRingtones
import app.mauri.cucuto.core.ringtone.contacts.AndroidContact
import app.mauri.cucuto.core.ringtone.sync.RingtoneProblem
import app.mauri.cucuto.core.ringtone.sync.RingtoneSync
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.core.ringtone.variants.VariantRules
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** One version chip: a ringtone variant of the character. */
@Immutable
data class VariantChip(
    val file: String,
    val label: String,
)

/**
 * What the phone ringtone section shows (#52).
 *
 * @property contactId The chat's contact, null when it has none (no voice yet).
 * @property using Whether the contact has a phone ringtone (the user may have stopped it).
 * @property modes The rules worth offering for this character's variants, in display order.
 * @property ringingWith The version Cucuto put on the Android contact, when one is there.
 * @property exportTitle What the ring file is called in the system's ringtone lists (for the WhatsApp hint).
 * @property ringingCharacter The character that rings, for its album cover (null when not installed).
 * @property mixed The user picked this ringtone: it no longer follows the messages' character (#53, ADR 0063).
 * @property kept It follows the messages, but their character has no ringtones, so the one that rang before stays.
 * @property voiceName The messages' character, when the chat has one.
 * @property previewSound The version that rings now, for the play button (#246); null when there is nothing to play.
 * @property preview Its preview while it rings or is paused, with its progress (owner update 2026-10-24, #242).
 * @property choice The stored kind of ringtone (ADR 0073), null when nothing is stored.
 * @property setInContacts The ringtone was changed in Contacts after Cucuto, and Cucuto respects it (#372): the contact
 *   rings with that one, not [choice], until the user takes it back or saves a new choice in the picker.
 */
@Immutable
data class PhoneRingtoneUiState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val contactId: Long? = null,
    val characterName: String? = null,
    val hasCharacter: Boolean = false,
    val variants: List<VariantChip> = emptyList(),
    val linkedName: String? = null,
    val linked: Boolean = false,
    val using: Boolean = false,
    val mode: RingtoneMode = RingtoneMode.PACK,
    val fixedVariant: String? = null,
    val modes: List<RingtoneMode> = emptyList(),
    val ringingWith: String? = null,
    val exportTitle: String? = null,
    val problem: RingtoneProblem? = null,
    val syncing: Boolean = false,
    val suggestions: List<AndroidContact> = emptyList(),
    val needsPhonePermission: Boolean = false,
    val ringingCharacter: VoiceCharacter? = null,
    val mixed: Boolean = false,
    val kept: Boolean = false,
    val voiceName: String? = null,
    val previewSound: RingSound? = null,
    val preview: PreviewPlaying<RingSound>? = null,
    val choice: RingtoneChoice? = null,
    val setInContacts: Boolean = false,
) {
    /** "Android default": the phone's default ringtone (ADR 0073). */
    val isDefault: Boolean get() = choice == RingtoneChoice.ANDROID_DEFAULT

    /** "Leave as is": Cucuto doesn't touch the contact's ringtone (ADR 0073). */
    val isUnchanged: Boolean get() = choice == RingtoneChoice.UNCHANGED

    /** The set-up card has something to set up: a character that rings, or Android default (to clear one). */
    val needsSetUp: Boolean get() = isDefault || (hasRingtone && !isUnchanged)

    /**
     * A problem the set-up card shows. Not linked is the card's link prompt, no ringtones means nothing to set up, and
     * a render failure is none while an earlier ring file still rings.
     */
    val shownProblem: RingtoneProblem?
        get() =
            if (setInContacts) {
                RingtoneProblem.ChangedElsewhere
            } else {
                problem?.takeUnless {
                    it == RingtoneProblem.NotLinked || it == RingtoneProblem.NoRingtones ||
                        (it == RingtoneProblem.RenderFailed && ringingWith != null)
                }
            }

    /**
     * The set-up card asks for something (#348): turn phone ringtones on, link the Android contact, use the ringtone,
     * allow the phone permission, or fix a problem. Otherwise what rings is only the "Phone calls" row's.
     */
    val needsAction: Boolean
        get() =
            (needsSetUp || setInContacts) &&
                (!enabled || !linked || !using || needsPhonePermission || shownProblem != null)

    /** The linked contact and the WhatsApp hint sit under the "Phone calls" row (#348). */
    val showsLink: Boolean get() = !loading && contactId != null && needsSetUp && enabled && linked

    /** The preview is ringing (not paused). */
    val previewing: Boolean get() = preview != null && !preview.paused

    /** Something rings (or will once linked and turned on): a character with ringtones. */
    val hasRingtone: Boolean get() = variants.isNotEmpty()

    /** The rule as the summary row says it: "One version" names the version. */
    val fixedLabel: String?
        get() = if (mode == RingtoneMode.FIXED) variants.firstOrNull { it.file == fixedVariant }?.label else null
}

/**
 * The phone ringtone of one chat (#52, ADR 0039): what rings (the "Phone calls" row of contact detail, with a preview
 * from the start of the song, #246), link its Android contact, or take a ringtone back after it was changed in
 * Contacts. The character, version and rule, or "Android default" and "Leave as is" (ADR 0073), are chosen in the
 * ringtone picker (#53), independently of the messages' character (ADR 0063). Everything goes through
 * [PhoneRingtones], whose sync writes Android contacts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = PhoneRingtoneViewModel.Factory::class)
class PhoneRingtoneViewModel
    @AssistedInject
    constructor(
        @Assisted private val chat: ChatKey,
        private val ringtones: PhoneRingtones,
        chats: ChatDirectory,
        private val characters: CharacterDirectory,
        preview: RingtonePreview,
        private val clock: WallClock,
    ) : ViewModel() {
        private val suggestions = MutableStateFlow<List<AndroidContact>>(emptyList())
        private val permissionTick = MutableStateFlow(0)
        private val playback = RingtonePlayback<RingSound>(viewModelScope, preview)
        private var contactId: Long? = null

        private val found =
            chats.observeChats().map { all -> all.firstOrNull { chat in it.keys } }.distinctUntilChanged()

        val state: StateFlow<PhoneRingtoneUiState> =
            found
                .map { it?.contactId to it?.voice }
                .distinctUntilChanged()
                .flatMapLatest { (id, voice) ->
                    contactId = id
                    if (id == null) {
                        ringtones.enabled.map { PhoneRingtoneUiState(loading = false, enabled = it) }
                    } else {
                        combine(
                            ringtones.observeContact(id),
                            suggestions,
                            permissionTick,
                            characters.observePacks(),
                            playback.playing,
                        ) { contact, suggested, _, packs, playing ->
                            fun find(
                                packId: String,
                                characterId: String,
                            ) = packs.firstOrNull { it.packId == packId }?.characters?.firstOrNull {
                                it.id ==
                                    characterId
                            }
                            val ringing = contact.character?.let { find(it.packId, it.characterId) }
                            val voiceName = voice?.let { find(it.packId, it.characterId)?.name }
                            toState(contact, suggested, ringing, voiceName).let { state ->
                                state.copy(preview = playing?.takeIf { it.key == state.previewSound })
                            }
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PhoneRingtoneUiState())

        private fun toState(
            contact: ContactPhoneRingtone,
            suggested: List<AndroidContact>,
            ringing: VoiceCharacter?,
            voiceName: String?,
        ): PhoneRingtoneUiState {
            val variants = contact.character?.variants.orEmpty()
            val mode = contact.ringtone?.mode ?: RingtoneMode.PACK
            val current = RingtoneSync.variantOf(contact.write, variants)
            val own = contact.ringtone?.packId != null
            val follows = contact.ringtone?.followsVoice ?: true
            val previewVariant =
                current
                    ?: variants
                        .takeIf {
                            it.isNotEmpty()
                        }?.let { previewVariant(it, mode, contact.ringtone?.variant, contact.ringtone?.rules) }
            return PhoneRingtoneUiState(
                loading = false,
                enabled = contact.enabled,
                contactId = contact.contactId,
                characterName = contact.character?.characterName,
                hasCharacter = contact.character != null,
                variants = variants.distinctBy { it.file }.map { VariantChip(it.file, it.chipLabel()) },
                linkedName = contact.linkedName,
                linked = contact.linkedLookupKey != null,
                using = contact.ringtone.let { it != null && it.choice != RingtoneChoice.UNCHANGED },
                mode = mode,
                fixedVariant = contact.ringtone?.variant,
                modes = modesFor(variants),
                ringingWith = current?.chipLabel()?.takeIf { contact.write?.userChanged != true },
                exportTitle =
                    current
                        ?.let { v -> contact.character?.let { RingtoneSync.title(it, v) } }
                        ?.takeIf { contact.write?.writtenUri != null },
                problem =
                    contact.problem.takeUnless {
                        contact.linkedLookupKey == null &&
                            it == RingtoneProblem.NotLinked
                    },
                syncing = contact.syncing,
                suggestions = if (contact.linkedLookupKey == null) suggested else emptyList(),
                needsPhonePermission =
                    mode == RingtoneMode.RANDOM &&
                        contact.ringtone?.choice == RingtoneChoice.CHARACTER &&
                        !ringtones.canReadPhoneState(),
                ringingCharacter = ringing,
                mixed = own && !follows,
                kept = own && follows,
                voiceName = voiceName,
                previewSound =
                    previewVariant?.let { v ->
                        contact.character?.fileOf(v)?.let { RingSound(it, v.loop, v.fadeInMs) }
                    },
                choice = contact.ringtone?.choice,
                setInContacts = setInContacts(contact),
            )
        }

        /** The version [mode] rings with now (day from 07:00 to 19:00 here; the sync uses the chosen place). */
        private fun previewVariant(
            variants: List<RingtoneVariant>,
            mode: RingtoneMode,
            fixed: String?,
            rules: String? = null,
        ): RingtoneVariant {
            if (mode == RingtoneMode.RANDOM) return variants.first()
            val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.now()), ZoneId.systemDefault())
            return VariantRules.pick(
                variants,
                mode,
                fixed,
                RuleContext(now),
                current = null,
                reroll = false,
                random = Random(0),
                rules = RingtoneRules.decode(rules),
            )
                ?: variants.first()
        }

        /** Rings the version that rings now like the call (#246, #242), or pauses and resumes it. */
        fun togglePreview() {
            val sound = state.value.previewSound ?: return
            playback.toggle(sound, sound)
        }

        fun stopPreview() = playback.stop()

        override fun onCleared() {
            playback.stop()
        }

        fun canWriteContacts(): Boolean = ringtones.canReadAndWriteContacts()

        /** After the contacts permission was granted: turns phone ringtones on and looks for this chat's contact. */
        fun enable() =
            launchSafely {
                if (ringtones.enable()) loadSuggestions()
            }

        /** Looks up Android contacts that match this chat (by phone number, else by name). */
        fun loadSuggestions() =
            launchSafely {
                val id = contactId ?: return@launchSafely
                if (ringtones.canReadAndWriteContacts()) suggestions.value = ringtones.suggestions(id)
            }

        fun link(contact: AndroidContact) =
            launchSafely {
                ringtones.link(contactId ?: return@launchSafely, contact)
            }

        /** A contact picker result (a `content://com.android.contacts/…` URI). */
        fun linkPicked(uri: String) =
            launchSafely {
                val picked = ringtones.resolvePicked(uri) ?: return@launchSafely
                ringtones.link(contactId ?: return@launchSafely, picked)
            }

        fun unlink() =
            launchSafely {
                ringtones.unlink(contactId ?: return@launchSafely)
                loadSuggestions()
            }

        /** Rings with the message character again, following it (ADR 0063). */
        fun use() =
            launchSafely {
                ringtones.choose(contactId ?: return@launchSafely, null, null, RingtoneMode.PACK, null)
            }

        fun takeOver() =
            launchSafely {
                ringtones.takeOver(contactId ?: return@launchSafely)
            }

        /** A permission answer came back: re-read what depends on it. */
        fun permissionsChanged() {
            permissionTick.value++
        }

        private fun launchSafely(block: suspend () -> Unit) {
            viewModelScope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The sync reports lasting problems in the state; a failed tap leaves things as they were.
                }
            }
        }

        @AssistedFactory
        interface Factory {
            fun create(chat: ChatKey): PhoneRingtoneViewModel
        }

        internal companion object {
            private const val STOP_TIMEOUT_MS = 5_000L

            private val SEASONS = setOf("spring", "summer", "autumn", "winter")

            /** The rules that make sense for [variants]: one version needs none, tags unlock time and season. */
            fun modesFor(variants: List<RingtoneVariant>): List<RingtoneMode> {
                if (variants.distinctBy { it.file }.size < 2) return emptyList()
                val tags = variants.flatMap { v -> v.tags.map { it.lowercase() } }.toSet()
                return buildList {
                    add(RingtoneMode.PACK)
                    add(RingtoneMode.RANDOM)
                    if ("day" in tags || "night" in tags) add(RingtoneMode.TIME)
                    if (tags.any { it in SEASONS }) add(RingtoneMode.SEASON)
                    add(RingtoneMode.FIXED)
                }
            }

            /**
             * Whether [contact]'s ringtone was changed in Contacts after Cucuto and Cucuto respects it (#372): its undo
             * record says so and the stored choice is one Cucuto would write ("Leave as is" never writes).
             */
            fun setInContacts(contact: ContactPhoneRingtone): Boolean =
                contact.linkedLookupKey != null &&
                    contact.write?.userChanged == true &&
                    contact.ringtone.let { it != null && it.choice != RingtoneChoice.UNCHANGED }

            private fun RingtoneVariant.chipLabel(): String =
                label ?: file.substringAfterLast('/').substringBeforeLast('.')
        }
    }
