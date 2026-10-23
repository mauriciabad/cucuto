package app.mauri.cucuto.core.data.repository

import androidx.room3.withWriteTransaction
import app.mauri.cucuto.core.data.db.ContactDao
import app.mauri.cucuto.core.data.db.ContactEntity
import app.mauri.cucuto.core.data.db.ContactKeyEntity
import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.ContactWithKeys
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.di.WallClock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Contacts and their keys. A contact matches on any of its keys, so a person whose WhatsApp identity moves from a
 * phone JID to an `@lid` stays one contact once both keys are attached (by the user, or by a later matcher).
 */
@Singleton
class ContactRepository
    @Inject
    constructor(
        private val db: CucutoDatabase,
        private val dao: ContactDao,
        private val clock: WallClock,
    ) {
        fun observeContacts(): Flow<List<ContactWithKeys>> = dao.observeAll()

        fun observeContact(id: Long): Flow<ContactWithKeys?> = dao.observe(id)

        suspend fun get(id: Long): ContactWithKeys? = dao.get(id)

        suspend fun findByKey(
            packageName: String,
            key: String,
        ): ContactEntity? = dao.findByKey(packageName, key)

        /**
         * Returns the contact that owns `(packageName, key)`, creating it if needed, and records the sighting
         * (display name, time) on the key. Used for one-tap assignment from the recent senders feed.
         */
        suspend fun findOrCreate(
            packageName: String,
            key: String,
            displayName: String?,
            isGroup: Boolean,
        ): Long =
            db.withWriteTransaction {
                val now = clock.now()
                val existing = dao.findKey(packageName, key)
                if (existing != null) {
                    dao.touchKey(existing.id, displayName, now)
                    existing.contactId
                } else {
                    val contactId =
                        dao.insert(
                            ContactEntity(
                                displayName = displayName,
                                isGroup = isGroup || ContactKeyKind.classify(key) == ContactKeyKind.GROUP_JID,
                                shuffleSeed = Random.nextInt(),
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                    dao.insertKey(keyEntity(contactId, packageName, key, displayName, now))
                    contactId
                }
            }

        /** Creates a contact with no keys yet (for example from an Android contact). */
        suspend fun create(
            displayName: String?,
            isGroup: Boolean = false,
        ): Long {
            val now = clock.now()
            return dao.insert(
                ContactEntity(
                    displayName = displayName,
                    isGroup = isGroup,
                    shuffleSeed = Random.nextInt(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        /**
         * Attaches a messenger key to [contactId]. Returns false if the key already belongs to another contact
         * (merge them with [merge] instead).
         */
        suspend fun addKey(
            contactId: Long,
            packageName: String,
            key: String,
            displayName: String? = null,
        ): Boolean {
            require(packageName.isNotEmpty()) { "Messenger keys need a package; use addPhoneNumber for numbers" }
            return attach(contactId, packageName, key, displayName)
        }

        /** Attaches an E.164 phone number (used to re-match Android contacts on restore). */
        suspend fun addPhoneNumber(
            contactId: Long,
            e164: String,
        ): Boolean {
            require(ContactKeyKind.classify(e164) == ContactKeyKind.PHONE_NUMBER) { "Not an E.164 number: $e164" }
            return attach(contactId, ContactKeyEntity.NO_PACKAGE, e164, null)
        }

        suspend fun removeKey(keyId: Long) = dao.deleteKey(keyId)

        suspend fun rename(
            contactId: Long,
            displayName: String?,
        ) = edit(contactId) { it.copy(displayName = displayName) }

        suspend fun setGroupPolicy(
            contactId: Long,
            policy: GroupPolicy,
        ) = edit(contactId) { it.copy(groupPolicy = policy) }

        suspend fun linkAndroidContact(
            contactId: Long,
            lookupKey: String?,
            displayName: String?,
        ) = edit(contactId) { it.copy(androidLookupKey = lookupKey, androidDisplayName = displayName) }

        /**
         * Merges [fromContactId] into [intoContactId]: every key moves over, the target keeps its own settings and
         * assignment, and the source contact is deleted.
         */
        suspend fun merge(
            fromContactId: Long,
            intoContactId: Long,
        ) {
            require(fromContactId != intoContactId) { "Cannot merge a contact into itself" }
            db.withWriteTransaction {
                checkNotNull(dao.get(intoContactId)) { "Contact $intoContactId does not exist" }
                dao.moveKeys(fromContactId, intoContactId)
                dao.delete(fromContactId)
            }
        }

        suspend fun delete(contactId: Long) = dao.delete(contactId)

        private suspend fun attach(
            contactId: Long,
            packageName: String,
            key: String,
            displayName: String?,
        ): Boolean =
            db.withWriteTransaction {
                val existing = dao.findKey(packageName, key)
                when {
                    existing == null -> dao.insertKey(keyEntity(contactId, packageName, key, displayName, null)) != -1L
                    existing.contactId == contactId -> true
                    else -> false
                }
            }

        private suspend fun edit(
            contactId: Long,
            change: (ContactEntity) -> ContactEntity,
        ) {
            db.withWriteTransaction {
                val contact = dao.get(contactId)?.contact ?: return@withWriteTransaction
                dao.update(change(contact).copy(updatedAt = clock.now()))
            }
        }

        private fun keyEntity(
            contactId: Long,
            packageName: String,
            key: String,
            displayName: String?,
            seenAt: Long?,
        ) = ContactKeyEntity(
            contactId = contactId,
            packageName = packageName,
            value = key,
            kind = ContactKeyKind.classify(key),
            displayName = displayName,
            lastSeenAt = seenAt,
        )
    }
