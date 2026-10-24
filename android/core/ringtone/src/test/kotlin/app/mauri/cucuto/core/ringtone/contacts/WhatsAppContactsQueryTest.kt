package app.mauri.cucuto.core.ringtone.contacts

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.WhatsAppContact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * "Add a chat from contacts" reads only WhatsApp's entries of Android's contacts (ADR 0065): [ResolverWhatsAppContacts]
 * against [FakeContactsData], a made-up contacts provider. No real names or numbers.
 */
@RunWith(AndroidJUnit4::class)
class WhatsAppContactsQueryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: FakeContactsData
    private val reader by lazy { ResolverWhatsAppContacts(app) }

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeContactsData::class.java, ContactsContract.AUTHORITY)
        provider.rows +=
            listOf(
                FakeContactsData.Row("lk-zoe", "Zoe Test", PROFILE, "34600000003@s.whatsapp.net"),
                FakeContactsData.Row("lk-ana", "Ana Test", PROFILE, "34600000001@s.whatsapp.net"),
                // A second WhatsApp number of the same contact.
                FakeContactsData.Row("lk-ana", "Ana Test", PROFILE, "447700900001@s.whatsapp.net"),
                // The same contact in WhatsApp Business.
                FakeContactsData.Row("lk-ana", "Ana Test", BUSINESS, "34600000001@s.whatsapp.net"),
                FakeContactsData.Row("lk-eva", "Éva Test", PROFILE, "34600000004@s.whatsapp.net"),
                // Not WhatsApp profile rows: never returned.
                FakeContactsData.Row("lk-ana", "Ana Test", "vnd.android.cursor.item/phone_v2", "+34 600 000 001"),
                FakeContactsData.Row("lk-ana", "Ana Test", VOIP, "34600000001@s.whatsapp.net"),
                FakeContactsData.Row("lk-signal", "Sam Test", "vnd.android.cursor.item/vnd.org.example.chat", "x"),
                // Odd WhatsApp rows: skipped.
                FakeContactsData.Row("lk-odd", "Odd Test", PROFILE, "not-a-jid"),
                FakeContactsData.Row("lk-null", "Null Test", PROFILE, null),
            )
    }

    @Test
    fun `nothing is read without the contacts permission`() =
        runTest {
            assertFalse(reader.canRead())
            assertNull(reader.load())
            assertTrue(provider.queries.isEmpty())
        }

    @Test
    fun `one query on WhatsApp's profile rows, grouped per contact and app, sorted by name`() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)

            val people = reader.load()!!

            assertEquals(
                listOf(
                    WhatsAppContact(
                        "lk-ana",
                        "Ana Test",
                        ChatKey.WHATSAPP,
                        listOf("34600000001@s.whatsapp.net", "447700900001@s.whatsapp.net"),
                    ),
                    WhatsAppContact("lk-ana", "Ana Test", ChatKey.WHATSAPP_BUSINESS, listOf("34600000001@s.whatsapp.net")),
                    WhatsAppContact("lk-eva", "Éva Test", ChatKey.WHATSAPP, listOf("34600000004@s.whatsapp.net")),
                    WhatsAppContact("lk-zoe", "Zoe Test", ChatKey.WHATSAPP, listOf("34600000003@s.whatsapp.net")),
                ),
                people,
            )
            assertEquals(listOf("+34600000001", "+447700900001"), people.first().phoneNumbers)

            val query = provider.queries.single()
            assertEquals(Data.CONTENT_URI, query.uri)
            assertEquals(listOf(Data.MIMETYPE, Data.DATA1, Data.LOOKUP_KEY, Data.DISPLAY_NAME), query.projection)
            assertEquals(listOf(PROFILE, BUSINESS), query.args)
        }

    @Test
    fun `a contacts provider that fails gives an empty list, not a crash`() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
            provider.fail = true

            assertEquals(emptyList<WhatsAppContact>(), reader.load())
        }

    private companion object {
        const val PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        const val BUSINESS = "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"
        const val VOIP = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    }
}

/**
 * A made-up `com.android.contacts` provider with only the `data` table, answering `mimetype IN (…)` selections the way
 * the real provider does. Records every query, so tests can check what was asked for.
 */
class FakeContactsData : ContentProvider() {
    data class Row(
        val lookupKey: String,
        val name: String,
        val mimeType: String,
        val data1: String?,
    )

    data class Query(
        val uri: Uri,
        val projection: List<String>?,
        val selection: String?,
        val args: List<String>?,
    )

    val rows = mutableListOf<Row>()
    val queries = mutableListOf<Query>()
    var fail = false

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queries += Query(uri, projection?.toList(), selection, selectionArgs?.toList())
        check(!fail) { "provider down" }
        val columns = projection ?: arrayOf(Data.MIMETYPE, Data.DATA1, Data.LOOKUP_KEY, Data.DISPLAY_NAME)
        val cursor = MatrixCursor(columns)
        if (uri != Data.CONTENT_URI) return cursor
        val wanted = if (selection?.startsWith("${Data.MIMETYPE} IN") == true) selectionArgs?.toSet() else null
        for (row in rows) {
            if (wanted != null && row.mimeType !in wanted) continue
            cursor.addRow(
                columns.map { column ->
                    when (column) {
                        Data.MIMETYPE -> row.mimeType
                        Data.DATA1 -> row.data1
                        Data.LOOKUP_KEY -> row.lookupKey
                        Data.DISPLAY_NAME -> row.name
                        else -> null
                    }
                },
            )
        }
        return cursor
    }

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
}
