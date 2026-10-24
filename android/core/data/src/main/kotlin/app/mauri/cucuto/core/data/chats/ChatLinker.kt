package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.db.ContactKeyEntity
import app.mauri.cucuto.core.data.db.ContactWithKeys
import app.mauri.cucuto.core.data.db.SeenSenderDao
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.SeenSenderRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Cucuto already has of Android contacts' WhatsApp and Signal entries, to mark them in the "Add a chat from
 * contacts" list.
 *
 * @property linked `(package, lookup key)` of every chat linked to an Android contact.
 * @property keys Every messenger key of every contact.
 * @property people Lookup keys of every 1:1 contact linked to an Android contact: on Signal, that person already has
 *   their voice through the link (ADR 0081).
 */
data class KnownContacts(
    val linked: Set<Pair<String, String>> = emptySet(),
    val keys: Set<ChatKey> = emptySet(),
    val people: Set<String> = emptySet(),
) {
    /** Whether [entry] is already a chat: one of its numbers, or its Android contact for that app (any app on Signal). */
    operator fun contains(entry: WhatsAppContact): Boolean =
        if (entry.isSignal) {
            entry.lookupKey in people || entry.chatKey in keys
        } else {
            (entry.packageName to entry.lookupKey) in linked ||
                entry.phoneJids.any { ChatKey(entry.packageName, it) in keys }
        }
}

/**
 * Chats added from Android's contacts before they message, and linking them to their WhatsApp `@lid` (ADR 0065).
 * Every change republishes the routing table (the publisher observes the database).
 */
interface ContactChats {
    /** What is already in Cucuto ([KnownContacts]). */
    suspend fun known(): KnownContacts

    /**
     * Saves the [picked] people as chats waiting for their first message, linked to their Android contacts, and links
     * any of them who already messaged. [all] is every WhatsApp entry the list showed, for the namesake check. People
     * already in Cucuto are linked to their Android contact, never added twice. Returns each person's chat, in order.
     */
    suspend fun addFromContacts(
        picked: List<WhatsAppContact>,
        all: List<WhatsAppContact>,
    ): List<ChatKey>

    /** The user's answer to "Who is this?": [sighting] is the waiting chat [contactId]. */
    suspend fun confirmLink(
        sighting: ChatKey,
        contactId: Long,
    )

    /**
     * Links every recent sender that matches a waiting chat exactly and uniquely ([ChatLinkRules]). [entries] are the
     * WhatsApp entries of Android's contacts if already in memory; otherwise they are read only when some name matches.
     * Returns how many chats were linked.
     */
    suspend fun linkWaiting(entries: List<WhatsAppContact>? = null): Int
}

/** [ContactChats] over the Room repositories. */
@Singleton
class ChatLinker
    @Inject
    constructor(
        private val contacts: ContactRepository,
        private val seenSenders: SeenSenderRepository,
        private val whatsApp: WhatsAppContactsSource,
    ) : ContactChats {
        private val mutex = Mutex()

        override suspend fun known(): KnownContacts {
            val all = contacts.observeContacts().first()
            val linked = HashSet<Pair<String, String>>()
            val keys = HashSet<ChatKey>()
            val people = HashSet<String>()
            for (contact in all) {
                val messenger = contact.keys.filter { it.packageName != ContactKeyEntity.NO_PACKAGE }
                messenger.mapTo(keys) { ChatKey(it.packageName, it.value) }
                val lookupKey = contact.contact.androidLookupKey ?: continue
                messenger.mapTo(linked) { it.packageName to lookupKey }
                if (!contact.contact.isGroup) people += lookupKey
            }
            return KnownContacts(linked, keys, people)
        }

        override suspend fun addFromContacts(
            picked: List<WhatsAppContact>,
            all: List<WhatsAppContact>,
        ): List<ChatKey> {
            val saved =
                mutex.withLock {
                    val namesakes = ChatLinkRules.namesakesOf(all)
                    picked.filter { it.isSignal || it.phoneJids.isNotEmpty() }.map { person ->
                        if (person.isSignal) saveSignal(person) else save(person, namesakes)
                    }
                }
            linkWaiting(all)
            return saved
        }

        private suspend fun save(
            person: WhatsAppContact,
            namesakes: (String, String) -> Set<String>,
        ): ChatKey {
            val existing =
                person.phoneJids.firstNotNullOfOrNull { contacts.findByKey(person.packageName, it) }?.id
                    ?: alreadyLinked(person)
                    ?: sameNameChat(person, namesakes)
            val contactId = existing ?: contacts.create(person.displayName)
            val current = contacts.get(contactId)?.contact
            if (current?.androidLookupKey == null) {
                contacts.linkAndroidContact(contactId, person.lookupKey, person.displayName)
            }
            var key: ChatKey? = null
            for (jid in person.phoneJids) {
                if (contacts.addKey(contactId, person.packageName, jid, person.displayName) && key == null) {
                    key = ChatKey(person.packageName, jid)
                }
            }
            for (number in person.phoneNumbers) runCatching { contacts.addPhoneNumber(contactId, number) }
            return key ?: person.chatKey
        }

        /**
         * A Signal entry (ADR 0081): the person who already owns its contact key (stored, or through a chat linked to
         * the same Android contact) stays as they are; anyone else becomes a contact linked to the Android contact,
         * with the contact key stored so the chat shows on Home before their first Signal message.
         */
        private suspend fun saveSignal(person: WhatsAppContact): ChatKey {
            val key = person.chatKey
            if (contacts.findByKey(key.packageName, key.conversationKey) != null) return key
            val contactId = contacts.create(person.displayName)
            contacts.linkAndroidContact(contactId, person.lookupKey, person.displayName)
            contacts.addKey(contactId, key.packageName, key.conversationKey, person.displayName)
            for (number in person.phoneNumbers) runCatching { contacts.addPhoneNumber(contactId, number) }
            return key
        }

        /** A 1:1 contact already linked to [person]'s Android contact with a key in the same app (phone ringtones). */
        private suspend fun alreadyLinked(person: WhatsAppContact): Long? =
            contacts
                .observeContacts()
                .first()
                .firstOrNull { contact ->
                    !contact.contact.isGroup &&
                        contact.contact.androidLookupKey == person.lookupKey &&
                        contact.keys.any { it.packageName == person.packageName }
                }?.contact
                ?.id

        /**
         * A chat made from a recent sender, with no Android link, that is [person] by the same rules as linking: the
         * only such chat with that name, and no namesake among the WhatsApp entries. Home then never shows them twice.
         */
        private suspend fun sameNameChat(
            person: WhatsAppContact,
            namesakes: (String, String) -> Set<String>,
        ): Long? {
            val name = ChatLinkRules.nameKey(person.displayName) ?: return null
            if (namesakes(person.packageName, name) != setOf(person.lookupKey)) return null
            return contacts
                .observeContacts()
                .first()
                .filter { contact ->
                    !contact.contact.isGroup &&
                        contact.contact.androidLookupKey == null &&
                        !isWaiting(contact) &&
                        contact.keys.any { it.packageName == person.packageName } &&
                        ChatLinkRules.nameKey(nameOf(contact)) == name
                }.singleOrNull()
                ?.contact
                ?.id
        }

        private fun nameOf(contact: ContactWithKeys): String? =
            contact.contact.displayName ?: contact.keys.firstNotNullOfOrNull { it.displayName }

        override suspend fun confirmLink(
            sighting: ChatKey,
            contactId: Long,
        ) {
            mutex.withLock {
                val seen = seenSenders.get(sighting.packageName, sighting.conversationKey)
                contacts.addKey(contactId, sighting.packageName, sighting.conversationKey, seen?.displayName)
            }
            // One answer can make another match unique.
            linkWaiting()
        }

        override suspend fun linkWaiting(entries: List<WhatsAppContact>?): Int =
            mutex.withLock {
                val all = contacts.observeContacts().first()
                val waiting = all.mapNotNull(::waitingChat)
                if (waiting.isEmpty()) return@withLock 0
                val claimed =
                    all
                        .flatMap { it.keys }
                        .filter { it.packageName != ContactKeyEntity.NO_PACKAGE }
                        .mapTo(HashSet()) { ChatKey(it.packageName, it.value) }
                val seen = seenSenders.observeRecent(SeenSenderDao.CAPACITY).first()
                val sightings =
                    seen
                        .filter { it.isPerson() && ChatKey(it.packageName, it.conversationKey) !in claimed }
                        .map { UnlinkedSighting(ChatKey(it.packageName, it.conversationKey), it.displayName) }
                // Contacts are read only once some name matches.
                if (ChatLinkRules.plan(waiting, sightings, namesakes = null).questions.isEmpty()) return@withLock 0
                val settled =
                    all
                        .filter { !it.contact.isGroup && !isWaiting(it) }
                        .flatMap { contact ->
                            val lookupKey = contact.contact.androidLookupKey ?: return@flatMap emptyList()
                            contact.keys
                                .filter { it.packageName != ContactKeyEntity.NO_PACKAGE }
                                .map { it.packageName to lookupKey }
                        }.toSet()
                val namesakes = (entries ?: whatsApp.load())?.let { ChatLinkRules.namesakesOf(it, settled) }
                val plan = ChatLinkRules.plan(waiting, sightings, namesakes)
                val names = sightings.associate { it.key to it.name }
                plan.links.count { (key, contactId) ->
                    contacts.addKey(contactId, key.packageName, key.conversationKey, names[key])
                }
            }
    }
