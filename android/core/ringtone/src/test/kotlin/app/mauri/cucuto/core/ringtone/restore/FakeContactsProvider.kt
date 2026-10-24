package app.mauri.cucuto.core.ringtone.restore

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.PhoneLookup

/**
 * A made-up contacts provider for Robolectric (no real names or numbers): just the queries `ResolverAndroidContacts`
 * makes, answered from [people]. Lookup keys here are this "phone's" own, like on a real new phone.
 *
 * - `contacts/lookup/<key>[/<id>]`: one contact (also what a contact picker returns);
 * - `phone_lookup/<number>`: contacts with that number (digits compared, like Android's loose match);
 * - `contacts` with `display_name = ? COLLATE NOCASE`;
 * - `data/phones` with `contact_id = ?`.
 */
class FakeContactsProvider : ContentProvider() {
    data class Person(
        val id: Long,
        val lookupKey: String,
        val name: String,
        val numbers: List<String> = emptyList(),
        val ringtone: String? = null,
    )

    val people = mutableListOf<Person>()

    /** Every URI queried, to check that nothing else is read. */
    val queried = mutableListOf<Uri>()

    /** The thread of every query, to check that callers on the main thread are never blocked (#330). */
    val threads = mutableListOf<Thread>()

    fun add(
        lookupKey: String,
        name: String,
        vararg numbers: String,
    ) {
        people += Person(people.size + 1L, lookupKey, name, numbers.toList())
    }

    /** What a contact picker hands back for [lookupKey]. */
    fun pickerUri(lookupKey: String): Uri {
        val person = people.single { it.lookupKey == lookupKey }
        return Contacts.getLookupUri(person.id, person.lookupKey)
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queried += uri
        threads += Thread.currentThread()
        val segments = uri.pathSegments
        val columns = projection ?: arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME)
        val cursor = MatrixCursor(columns)
        when {
            segments.size >= 3 && segments[0] == "contacts" && segments[1] == "lookup" -> {
                people.filter { it.lookupKey == segments[2] }.forEach { cursor.addPerson(it, columns, null) }
            }

            segments.size == 2 && segments[0] == "phone_lookup" -> {
                val wanted = digits(segments[1])
                people
                    .filter { person -> person.numbers.any { digits(it).endsWith(wanted.takeLast(LOOSE_DIGITS)) } }
                    .forEach { cursor.addPerson(it, columns, null) }
            }

            segments == listOf("contacts") && selection == "${Contacts.DISPLAY_NAME} = ? COLLATE NOCASE" -> {
                val name = selectionArgs!!.single()
                people
                    .filter {
                        it.name.equals(
                            name,
                            ignoreCase = true,
                        )
                    }.forEach { cursor.addPerson(it, columns, null) }
            }

            segments == listOf("data", "phones") && selection == "${Phone.CONTACT_ID} = ?" -> {
                val id = selectionArgs!!.single().toLong()
                people.filter { it.id == id }.forEach { person ->
                    person.numbers.forEach { cursor.addPerson(person, columns, it) }
                }
            }

            else -> {
                error("Unexpected contacts query: $uri ($selection)")
            }
        }
        return cursor
    }

    private fun MatrixCursor.addPerson(
        person: Person,
        columns: Array<out String>,
        number: String?,
    ) {
        addRow(
            columns.map { column ->
                when (column) {
                    Contacts._ID -> person.id
                    Contacts.LOOKUP_KEY, PhoneLookup.LOOKUP_KEY -> person.lookupKey
                    Contacts.DISPLAY_NAME, PhoneLookup.DISPLAY_NAME -> person.name
                    Contacts.CUSTOM_RINGTONE -> person.ringtone
                    Phone.NORMALIZED_NUMBER -> number?.let { "+" + digits(it) }
                    Phone.NUMBER -> number
                    else -> null
                }
            },
        )
    }

    private fun digits(number: String) = number.filter(Char::isDigit)

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    private companion object {
        /** Android's loose phone number match compares the trailing digits. */
        const val LOOSE_DIGITS = 9
    }
}
