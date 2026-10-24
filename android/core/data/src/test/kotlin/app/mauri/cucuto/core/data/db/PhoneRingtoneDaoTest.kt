package app.mauri.cucuto.core.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneRingtoneDaoTest {
    private val db = inMemoryDatabase()
    private val dao = db.phoneRingtoneDao()

    @After fun close() = db.close()

    private suspend fun contact(
        id: Long,
        lookupKey: String?,
    ) = db.contactDao().insert(
        ContactEntity(
            id = id,
            displayName = "C$id",
            androidLookupKey = lookupKey,
            shuffleSeed = 1,
            createdAt = 1,
            updatedAt = 1,
        ),
    )

    @Test
    fun syncRowsFollowTheVoiceUnlessTheRingtoneNamesItsOwnCharacter() =
        runTest {
            contact(1, "ada")
            contact(2, null)
            db.assignmentDao().upsert(AssignmentEntity(1, "pack.a", "frog", updatedAt = 1))
            db.assignmentDao().upsert(AssignmentEntity(2, "pack.a", "frog", updatedAt = 1))
            dao.upsert(PhoneRingtoneEntity(contactId = 1, updatedAt = 1))
            dao.upsert(PhoneRingtoneEntity(2, "pack.b", "kk", RingtoneMode.FIXED, "live.ogg", 1))

            val rows = dao.syncRows()
            assertEquals(PhoneRingtoneRow(1, "ada", "C1", "pack.a", "frog", RingtoneMode.PACK, null), rows[0])
            assertEquals(PhoneRingtoneRow(2, null, "C2", "pack.b", "kk", RingtoneMode.FIXED, "live.ogg"), rows[1])
        }

    @Test
    fun deletingAContactDropsItsRingtoneButKeepsTheUndoRecord() =
        runTest {
            contact(1, "ada")
            dao.upsert(PhoneRingtoneEntity(contactId = 1, updatedAt = 1))
            dao.upsertWrite(
                ContactRingtoneWriteEntity("ada", 1, "content://original", "content://ours", "a.ogg", false, 1),
            )
            db.contactDao().delete(1)

            assertNull(dao.get(1))
            assertTrue(dao.syncRows().isEmpty())
            assertEquals("content://original", dao.writes().single().originalRingtone)
        }
}
