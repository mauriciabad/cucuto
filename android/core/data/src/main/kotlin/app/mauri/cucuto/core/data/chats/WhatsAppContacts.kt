package app.mauri.cucuto.core.data.chats

import app.mauri.cucuto.core.data.db.ContactKeyKind

/**
 * An Android contact with a WhatsApp (or WhatsApp Business) entry, as WhatsApp's own contacts sync wrote it
 * (ADR 0065). One per Android contact and app. Never log it: it names a person.
 *
 * @property lookupKey `ContactsContract.Contacts.LOOKUP_KEY` of the Android contact.
 * @property displayName The Android contact's display name, which WhatsApp also shows on its notifications.
 * @property packageName The app the entry belongs to: [ChatKey.WHATSAPP] or [ChatKey.WHATSAPP_BUSINESS].
 * @property phoneJids The entry's phone JIDs (`<number>@s.whatsapp.net`), at least one.
 */
data class WhatsAppContact(
    val lookupKey: String,
    val displayName: String?,
    val packageName: String,
    val phoneJids: List<String>,
) {
    /** The phone JIDs as E.164 numbers, for re-matching after a restore and to tell namesakes apart. */
    val phoneNumbers: List<String>
        get() =
            phoneJids
                .map { "+" + it.substringBefore('@') }
                .filter { ContactKeyKind.classify(it) == ContactKeyKind.PHONE_NUMBER }
                .distinct()

    /** The chat this entry becomes: its first phone JID. */
    val chatKey: ChatKey get() = ChatKey(packageName, phoneJids.first())
}

/**
 * Reads the WhatsApp entries of Android's contacts (ADR 0065): nothing else of the contacts, and nothing of WhatsApp's
 * own files. Implemented over `ContactsContract` in `:core:ringtone`.
 */
interface WhatsAppContactsSource {
    /** Whether Cucuto may read contacts (`READ_CONTACTS`). */
    fun canRead(): Boolean

    /** Every Android contact with a WhatsApp entry, sorted by name, or null without the permission. */
    suspend fun load(): List<WhatsAppContact>?
}
