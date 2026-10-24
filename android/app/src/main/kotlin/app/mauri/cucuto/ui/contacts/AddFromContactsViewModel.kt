package app.mauri.cucuto.ui.contacts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ContactChats
import app.mauri.cucuto.core.data.chats.KnownContacts
import app.mauri.cucuto.core.data.chats.WhatsAppContact
import app.mauri.cucuto.core.data.chats.WhatsAppContactsSource
import app.mauri.cucuto.home.searchable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The steps of "Add a chat from contacts" (ADR 0065): the explainer always comes first. */
enum class AddStep {
    /** What is read, what is never read, and why the contacts permission is needed. */
    Explainer,

    /** Reading the WhatsApp entries. */
    Loading,

    /** The people to pick from (or none: [AddFromContactsState.total] is 0). */
    List,

    /** The contacts permission was refused: nothing was read. */
    Denied,
}

/** One Android contact's WhatsApp entry in the list. */
@Immutable
data class ContactRow(
    val entry: WhatsAppContact,
    val known: Boolean,
    val selected: Boolean,
) {
    val id: EntryId get() = EntryId(entry.packageName, entry.lookupKey)
}

/** An entry's identity: one per Android contact and app. */
data class EntryId(
    val packageName: String,
    val lookupKey: String,
)

/**
 * Everything "Add a chat from contacts" shows.
 *
 * @property rows The entries after search, by name.
 * @property total Every entry, before search (0: no WhatsApp entries at all).
 * @property selected How many new people are picked.
 * @property done The chats saved, once saving finished; the screen then closes.
 */
@Immutable
data class AddFromContactsState(
    val step: AddStep = AddStep.Explainer,
    val rows: List<ContactRow> = emptyList(),
    val query: String = "",
    val total: Int = 0,
    val selected: Int = 0,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val done: List<ChatKey>? = null,
)

/**
 * "Add a chat from contacts" (ADR 0065). Nothing is read until the user continues past the explainer and the
 * permission is granted; the list lives only in this view model, and only the people picked are saved.
 */
@HiltViewModel
class AddFromContactsViewModel
    @Inject
    constructor(
        private val source: WhatsAppContactsSource,
        private val contactChats: ContactChats,
    ) : ViewModel() {
        private var entries: List<WhatsAppContact> = emptyList()
        private var known = KnownContacts()
        private val picked = LinkedHashSet<EntryId>()
        private val _state = MutableStateFlow(AddFromContactsState())
        val state: StateFlow<AddFromContactsState> = _state.asStateFlow()

        /** Whether Android's prompt is needed before reading. */
        fun needsPermission(): Boolean = !source.canRead()

        /** Reads the entries: after the explainer, once the permission is granted. */
        fun load() {
            _state.update { it.copy(step = AddStep.Loading) }
            viewModelScope.launch {
                val loaded =
                    try {
                        source.load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                if (loaded == null) {
                    _state.update { it.copy(step = AddStep.Denied) }
                    return@launch
                }
                entries = loaded
                known = contactChats.known()
                _state.update { it.copy(step = AddStep.List) }
                publish()
            }
        }

        /** Android's prompt was refused. */
        fun denied() {
            _state.update { it.copy(step = AddStep.Denied) }
        }

        fun setQuery(value: String) {
            _state.update { it.copy(query = value) }
            publish()
        }

        /** Picks or unpicks [id]; people already in Cucuto stay as they are. */
        fun toggle(id: EntryId) {
            val entry = entries.firstOrNull { EntryId(it.packageName, it.lookupKey) == id } ?: return
            if (entry in known) return
            if (!picked.remove(id)) picked += id
            publish()
        }

        /** Saves the people picked as chats waiting for their first message. */
        fun save() {
            val people = entries.filter { EntryId(it.packageName, it.lookupKey) in picked && it !in known }
            if (people.isEmpty() || _state.value.saving) return
            _state.update { it.copy(saving = true) }
            viewModelScope.launch {
                try {
                    val saved = contactChats.addFromContacts(people, entries)
                    _state.update { it.copy(saving = false, done = saved) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _state.update { it.copy(saving = false, failed = true) }
                }
            }
        }

        fun clearFailure() {
            _state.update { it.copy(failed = false) }
        }

        private fun publish() {
            val needle = _state.value.query.searchable()
            val rows =
                entries
                    .filter {
                        needle.isEmpty() ||
                            it.displayName
                                .orEmpty()
                                .searchable()
                                .contains(needle)
                    }.map { entry ->
                        val isKnown = entry in known
                        val id = EntryId(entry.packageName, entry.lookupKey)
                        ContactRow(entry, known = isKnown, selected = isKnown || id in picked)
                    }
            _state.update { it.copy(rows = rows, total = entries.size, selected = picked.size) }
        }
    }
