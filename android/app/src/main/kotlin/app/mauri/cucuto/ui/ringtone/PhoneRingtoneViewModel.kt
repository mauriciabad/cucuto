package app.mauri.cucuto.ui.ringtone

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.ringtone.ContactPhoneRingtone
import app.mauri.cucuto.core.ringtone.PhoneRingtones
import app.mauri.cucuto.core.ringtone.contacts.AndroidContact
import app.mauri.cucuto.core.ringtone.sync.RingtoneProblem
import app.mauri.cucuto.core.ringtone.sync.RingtoneSync
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
 * @property mixed The ringtone is another character than the messages (#53).
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
) {
    /** The rule as the summary row says it: "One version" names the version. */
    val fixedLabel: String?
        get() = if (mode == RingtoneMode.FIXED) variants.firstOrNull { it.file == fixedVariant }?.label else null
}

/**
 * The phone ringtone of one chat (#52, ADR 0039): link its Android contact, stop it, or take a ringtone back after it
 * was changed in Contacts. The character, version and rule are chosen in the ringtone picker (#53). Everything goes
 * through [PhoneRingtones], whose sync writes Android contacts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = PhoneRingtoneViewModel.Factory::class)
class PhoneRingtoneViewModel
    @AssistedInject
    constructor(
        @Assisted private val chat: ChatKey,
        private val ringtones: PhoneRingtones,
        private val contacts: ContactRepository,
        private val characters: CharacterDirectory,
    ) : ViewModel() {
        private val suggestions = MutableStateFlow<List<AndroidContact>>(emptyList())
        private val permissionTick = MutableStateFlow(0)
        private var contactId: Long? = null

        val state: StateFlow<PhoneRingtoneUiState> =
            flow { emit(contacts.findByKey(chat.packageName, chat.conversationKey)?.id) }
                .flatMapLatest { id ->
                    contactId = id
                    if (id == null) {
                        ringtones.enabled.map { PhoneRingtoneUiState(loading = false, enabled = it) }
                    } else {
                        combine(
                            ringtones.observeContact(id),
                            suggestions,
                            permissionTick,
                            characters.observePacks(),
                        ) { contact, suggested, _, packs ->
                            val ringing =
                                contact.character?.let { c ->
                                    packs
                                        .firstOrNull { it.packId == c.packId }
                                        ?.characters
                                        ?.firstOrNull { it.id == c.characterId }
                                }
                            toState(contact, suggested, ringing)
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PhoneRingtoneUiState())

        private fun toState(
            contact: ContactPhoneRingtone,
            suggested: List<AndroidContact>,
            ringing: VoiceCharacter?,
        ): PhoneRingtoneUiState {
            val variants = contact.character?.variants.orEmpty()
            val mode = contact.ringtone?.mode ?: RingtoneMode.PACK
            val current = RingtoneSync.variantOf(contact.write, variants)
            return PhoneRingtoneUiState(
                loading = false,
                enabled = contact.enabled,
                contactId = contact.contactId,
                characterName = contact.character?.characterName,
                hasCharacter = contact.character != null,
                variants = variants.distinctBy { it.file }.map { VariantChip(it.file, it.chipLabel()) },
                linkedName = contact.linkedName,
                linked = contact.linkedLookupKey != null,
                using = contact.ringtone != null,
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
                    mode == RingtoneMode.RANDOM && contact.ringtone != null && !ringtones.canReadPhoneState(),
                ringingCharacter = ringing,
                mixed = contact.ringtone?.packId != null,
            )
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

        fun use() =
            launchSafely {
                ringtones.setRule(contactId ?: return@launchSafely, RingtoneMode.PACK)
            }

        fun stop() =
            launchSafely {
                ringtones.remove(contactId ?: return@launchSafely)
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

            private fun RingtoneVariant.chipLabel(): String =
                label ?: file.substringAfterLast('/').substringBeforeLast('.')
        }
    }
