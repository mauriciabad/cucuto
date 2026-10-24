package app.mauri.cucuto.core.ringtone.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** An Android contact the user can link a Cucuto contact to. Never log it: it names a person. */
data class AndroidContact(
    val lookupKey: String,
    val displayName: String?,
)

/** An Android contact with its current `CUSTOM_RINGTONE` (null: the default ringtone). */
data class AndroidContactRingtone(
    val lookupKey: String,
    val displayName: String?,
    val ringtone: String?,
)

/** The few things Cucuto does with Android contacts (ADR 0039). */
interface AndroidContacts {
    /** Whether Cucuto may read and change contacts (`READ_CONTACTS` and `WRITE_CONTACTS`). */
    fun canReadAndWrite(): Boolean

    /** Whether Cucuto may read contacts (`READ_CONTACTS`): enough to match restored contacts (#43). */
    fun canRead(): Boolean = canReadAndWrite()

    /** The contact behind [lookupKey] with its ringtone, or null when it no longer exists. */
    suspend fun find(lookupKey: String): AndroidContactRingtone?

    /** Sets the contact's `CUSTOM_RINGTONE` ([uri] null restores the default). Returns false if nothing changed. */
    suspend fun setRingtone(
        lookupKey: String,
        uri: String?,
    ): Boolean

    /** Contacts with the phone number [number] (any format Android can match). */
    suspend fun byPhoneNumber(number: String): List<AndroidContact>

    /** Contacts whose display name is exactly [name], ignoring case. */
    suspend fun byName(name: String): List<AndroidContact>

    /** The contact a contact picker returned ([contactUri] is a `content://com.android.contacts/…` URI). */
    suspend fun resolve(contactUri: String): AndroidContact?

    /** The contact's phone numbers in E.164, for re-matching after a restore. */
    suspend fun phoneNumbers(lookupKey: String): List<String>
}

/** [AndroidContacts] over `ContactsContract`. Every call returns empty or false without the permissions. */
@Singleton
class ResolverAndroidContacts
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AndroidContacts {
        private val resolver: ContentResolver get() = context.contentResolver

        override fun canReadAndWrite(): Boolean =
            granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.WRITE_CONTACTS)

        override fun canRead(): Boolean = granted(Manifest.permission.READ_CONTACTS)

        override suspend fun find(lookupKey: String): AndroidContactRingtone? {
            if (!granted(Manifest.permission.READ_CONTACTS)) return null
            val uri = Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, Uri.encode(lookupKey))
            val projection = arrayOf(Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME, Contacts.CUSTOM_RINGTONE)
            return runCatching {
                resolver.query(uri, projection, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) {
                        null
                    } else {
                        AndroidContactRingtone(c.getString(0) ?: lookupKey, c.getString(1), c.getString(2))
                    }
                }
            }.getOrNull()
        }

        override suspend fun setRingtone(
            lookupKey: String,
            uri: String?,
        ): Boolean {
            if (!canReadAndWrite()) return false
            val id = contactId(lookupKey) ?: return false
            val values = ContentValues().apply { put(Contacts.CUSTOM_RINGTONE, uri) }
            return runCatching {
                resolver.update(ContentUris.withAppendedId(Contacts.CONTENT_URI, id), values, null, null) > 0
            }.getOrDefault(false)
        }

        override suspend fun byPhoneNumber(number: String): List<AndroidContact> {
            if (!granted(Manifest.permission.READ_CONTACTS) || number.isBlank()) return emptyList()
            val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            return contacts(uri, arrayOf(PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME), null, null)
        }

        override suspend fun byName(name: String): List<AndroidContact> {
            if (!granted(Manifest.permission.READ_CONTACTS) || name.isBlank()) return emptyList()
            return contacts(
                Contacts.CONTENT_URI,
                arrayOf(Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME),
                "${Contacts.DISPLAY_NAME} = ? COLLATE NOCASE",
                arrayOf(name.trim()),
            )
        }

        override suspend fun resolve(contactUri: String): AndroidContact? {
            val uri = Uri.parse(contactUri)
            if (uri.authority != ContactsContract.AUTHORITY) return null
            return contacts(uri, arrayOf(Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME), null, null).firstOrNull()
        }

        override suspend fun phoneNumbers(lookupKey: String): List<String> {
            if (!granted(Manifest.permission.READ_CONTACTS)) return emptyList()
            val id = contactId(lookupKey) ?: return emptyList()
            val country =
                context
                    .getSystemService(TelephonyManager::class.java)
                    ?.networkCountryIso
                    ?.takeIf { it.isNotBlank() }
                    ?.uppercase(Locale.ROOT)
                    ?: Locale.getDefault().country
            return runCatching {
                resolver
                    .query(
                        Phone.CONTENT_URI,
                        arrayOf(Phone.NORMALIZED_NUMBER, Phone.NUMBER),
                        "${Phone.CONTACT_ID} = ?",
                        arrayOf(id.toString()),
                        null,
                    )?.use { c ->
                        buildSet {
                            while (c.moveToNext()) {
                                val e164 =
                                    c.getString(0)
                                        ?: c.getString(1)?.let { PhoneNumberUtils.formatNumberToE164(it, country) }
                                if (e164 != null && E164.matches(e164)) add(e164)
                            }
                        }.toList()
                    }.orEmpty()
            }.getOrDefault(emptyList())
        }

        private fun contactId(lookupKey: String): Long? {
            val uri = Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, Uri.encode(lookupKey))
            return runCatching {
                resolver.query(uri, arrayOf(Contacts._ID), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else null
                }
            }.getOrNull()
        }

        private fun contacts(
            uri: Uri,
            projection: Array<String>,
            selection: String?,
            args: Array<String>?,
        ): List<AndroidContact> =
            runCatching {
                resolver
                    .query(uri, projection, selection, args, null)
                    ?.use { c ->
                        buildList<AndroidContact> {
                            while (c.moveToNext()) {
                                val key = c.getString(0) ?: continue
                                if (none { it.lookupKey == key }) add(AndroidContact(key, c.getString(1)))
                            }
                        }
                    }.orEmpty()
            }.getOrDefault(emptyList())

        private fun granted(permission: String): Boolean =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        private companion object {
            val E164 = Regex("^\\+[1-9][0-9]{4,14}$")
        }
    }
