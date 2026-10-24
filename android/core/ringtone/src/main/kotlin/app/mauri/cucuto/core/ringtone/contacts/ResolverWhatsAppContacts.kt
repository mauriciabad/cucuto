package app.mauri.cucuto.core.ringtone.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.Data
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.WhatsAppContact
import app.mauri.cucuto.core.data.chats.WhatsAppContactsSource
import app.mauri.cucuto.core.data.db.ContactKeyKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The WhatsApp entries of Android's contacts, over `ContactsContract` (ADR 0065). WhatsApp's own contacts sync writes
 * one `Data` row per WhatsApp number of a saved contact, with WhatsApp's profile MIME type and the phone JID in
 * `DATA1`. This is one query on those rows only: the MIME type, the JID, the contact's lookup key and display name.
 * Nothing else of the contacts is read, nothing is kept, and without `READ_CONTACTS` nothing runs.
 */
@Singleton
class ResolverWhatsAppContacts
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : WhatsAppContactsSource {
        override fun canRead(): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        override suspend fun load(): List<WhatsAppContact>? {
            if (!canRead()) return null
            return withContext(Dispatchers.IO) {
                runCatching { query() }.getOrElse { emptyList() }
            }
        }

        private fun query(): List<WhatsAppContact> {
            val rows = ArrayList<Row>()
            context.contentResolver
                .query(
                    Data.CONTENT_URI,
                    PROJECTION,
                    "${Data.MIMETYPE} IN (?, ?, ?)",
                    arrayOf(WHATSAPP_PROFILE, BUSINESS_PROFILE, SIGNAL_CONTACT),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val packageName = packageOf(c.getString(0)) ?: continue
                        val value = c.getString(1)?.trim() ?: continue
                        val lookupKey = c.getString(2)?.takeIf { it.isNotEmpty() } ?: continue
                        if (packageName == ChatKey.SIGNAL) {
                            // Signal's entry holds the number as the contact has it; keep it only when it is E.164.
                            rows += Row(packageName, e164(value), lookupKey, c.getString(3))
                            continue
                        }
                        if (ContactKeyKind.classify(value) != ContactKeyKind.PHONE_JID) continue
                        rows += Row(packageName, value, lookupKey, c.getString(3))
                    }
                }
            return group(rows)
        }

        private data class Row(
            val packageName: String,
            /** The phone JID; for Signal, the E.164 number or null. */
            val jid: String?,
            val lookupKey: String,
            val name: String?,
        )

        internal companion object {
            /** WhatsApp's profile rows ("Message +34…" in the Contacts app). */
            const val WHATSAPP_PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"

            /** WhatsApp Business's, by the same naming (#229); not seen on the owner's phone yet. */
            const val BUSINESS_PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"

            /**
             * Signal's "Message +34…" rows (`SyncSystemContactLinksJob.MESSAGE_MIMETYPE`), number in `DATA1`. Molly
             * writes the same type (ADR 0081).
             */
            const val SIGNAL_CONTACT = "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact"

            private val PROJECTION = arrayOf(Data.MIMETYPE, Data.DATA1, Data.LOOKUP_KEY, Data.DISPLAY_NAME)

            fun packageOf(mimeType: String?): String? =
                when (mimeType) {
                    WHATSAPP_PROFILE -> ChatKey.WHATSAPP
                    BUSINESS_PROFILE -> ChatKey.WHATSAPP_BUSINESS
                    SIGNAL_CONTACT -> ChatKey.SIGNAL
                    else -> null
                }

            /** [raw] without spaces, dashes or brackets when it is an E.164 number, else null. */
            fun e164(raw: String): String? {
                val compact = raw.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }
                return compact.takeIf { ContactKeyKind.classify(it) == ContactKeyKind.PHONE_NUMBER }
            }

            /** One entry per Android contact and app, with all its numbers, sorted by name for the list. */
            private fun group(rows: List<Row>): List<WhatsAppContact> {
                val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
                return rows
                    .groupBy { it.packageName to it.lookupKey }
                    .map { (key, group) ->
                        WhatsAppContact(
                            lookupKey = key.second,
                            displayName = group.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) },
                            packageName = key.first,
                            phoneJids =
                                if (key.first ==
                                    ChatKey.SIGNAL
                                ) {
                                    emptyList()
                                } else {
                                    group.mapNotNull { it.jid }.distinct()
                                },
                            numbers =
                                if (key.first ==
                                    ChatKey.SIGNAL
                                ) {
                                    group.mapNotNull { it.jid }.distinct()
                                } else {
                                    emptyList()
                                },
                        )
                    }.sortedWith(
                        compareBy<WhatsAppContact, String?>(nullsLast(collator)) { it.displayName }
                            .thenBy { it.packageName }
                            .thenBy { it.lookupKey },
                    )
            }
        }
    }
