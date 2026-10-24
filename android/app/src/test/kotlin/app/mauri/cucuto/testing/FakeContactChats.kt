package app.mauri.cucuto.testing

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ContactChats
import app.mauri.cucuto.core.data.chats.KnownContacts
import app.mauri.cucuto.core.data.chats.WhatsAppContact
import app.mauri.cucuto.core.data.chats.WhatsAppContactsSource
import app.mauri.cucuto.core.data.settings.ContactsPromptStore

/** Records what the screens ask of [ContactChats]; nothing is stored. */
class FakeContactChats(
    var known: KnownContacts = KnownContacts(),
) : ContactChats {
    val added = mutableListOf<List<WhatsAppContact>>()
    val confirmed = mutableListOf<Pair<ChatKey, Long>>()
    var failAdd = false

    override suspend fun known(): KnownContacts = known

    override suspend fun addFromContacts(
        picked: List<WhatsAppContact>,
        all: List<WhatsAppContact>,
    ): List<ChatKey> {
        if (failAdd) error("disk full")
        added += picked
        return picked.map { it.chatKey }
    }

    override suspend fun confirmLink(
        sighting: ChatKey,
        contactId: Long,
    ) {
        confirmed += sighting to contactId
    }

    override suspend fun linkWaiting(entries: List<WhatsAppContact>?): Int = 0
}

/** Made-up WhatsApp entries of Android's contacts; [granted] is the contacts permission. */
class FakeWhatsAppContactsSource(
    var entries: List<WhatsAppContact> = emptyList(),
    var granted: Boolean = false,
) : WhatsAppContactsSource {
    var loads = 0

    override fun canRead(): Boolean = granted

    override suspend fun load(): List<WhatsAppContact>? {
        if (!granted) return null
        loads++
        return entries
    }
}

/** Remembers in memory whether Android stopped showing the contacts prompt. */
class FakeContactsPromptStore(
    var blocked: Boolean = false,
) : ContactsPromptStore {
    override suspend fun blocked(): Boolean = blocked

    override suspend fun setBlocked(blocked: Boolean) {
        this.blocked = blocked
    }
}
