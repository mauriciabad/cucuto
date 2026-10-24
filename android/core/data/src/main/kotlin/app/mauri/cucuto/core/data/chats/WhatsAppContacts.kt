package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.routing.ContactKeys

/**
 * An Android contact with a WhatsApp, WhatsApp Business or Signal entry, as the app's own contacts sync wrote it
 * (ADR 0065, ADR 0081). One per Android contact and app. Never log it: it names a person.
 *
 * @property lookupKey `ContactsContract.Contacts.LOOKUP_KEY` of the Android contact.
 * @property displayName The Android contact's display name, which WhatsApp also shows on its notifications.
 * @property packageName The app the entry belongs to: [ChatKey.WHATSAPP], [ChatKey.WHATSAPP_BUSINESS] or
 *   [ChatKey.SIGNAL] (Molly writes the same entries as Signal, so they show as Signal).
 * @property phoneJids WhatsApp only: the entry's phone JIDs (`<number>@s.whatsapp.net`), at least one.
 * @property numbers Signal only: the entry's E.164 numbers, when Signal wrote them in that form.
 */
data class WhatsAppContact(
    val lookupKey: String,
    val displayName: String?,
    val packageName: String,
    val phoneJids: List<String>,
    val numbers: List<String> = emptyList(),
) {
    /** Whether this is a Signal entry, keyed by its Android contact rather than a phone JID (ADR 0081). */
    val isSignal: Boolean get() = ContactKeys.isSignalFamily(packageName)

    /** The numbers as E.164, for re-matching after a restore and to tell namesakes apart. */
    val phoneNumbers: List<String>
        get() =
            (phoneJids.map { "+" + it.substringBefore('@') } + numbers)
                .filter { ContactKeyKind.classify(it) == ContactKeyKind.PHONE_NUMBER }
                .distinct()

    /** The chat this entry becomes: its first phone JID, or on Signal its Android contact. */
    val chatKey: ChatKey
        get() =
            if (isSignal) {
                ChatKey(
                    packageName,
                    ContactKeys.of(lookupKey),
                )
            } else {
                ChatKey(packageName, phoneJids.first())
            }
}

/**
 * Reads the WhatsApp and Signal entries of Android's contacts (ADR 0065, ADR 0081): nothing else of the contacts, and
 * nothing of the apps' own files. Implemented over `ContactsContract` in `:core:ringtone`.
 */
interface WhatsAppContactsSource {
    /** Whether Cucuto may read contacts (`READ_CONTACTS`). */
    fun canRead(): Boolean

    /** Every Android contact with a WhatsApp entry, sorted by name, or null without the permission. */
    suspend fun load(): List<WhatsAppContact>?
}
