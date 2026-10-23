package app.mauri.cucuto.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.mauri.cucuto.core.data.FakeClock
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssignmentRepositoryTest {
    private lateinit var db: CucutoDatabase
    private lateinit var contacts: ContactRepository
    private lateinit var assignments: AssignmentRepository
    private val clock = FakeClock()
    private var contactId = 0L

    @Before
    fun setUp() =
        runTest {
            db = inMemoryDatabase()
            contacts = ContactRepository(db, db.contactDao(), clock)
            assignments = AssignmentRepository(db, db.assignmentDao(), clock)
            contactId = contacts.findOrCreate("com.whatsapp", "34600000001@s.whatsapp.net", "Ada", isGroup = false)
        }

    @After
    fun tearDown() = db.close()

    @Test
    fun clipTogglesAndHapticSurviveReassigningTheSameCharacter() =
        runTest {
            assignments.assign(contactId, PACK, "frog")
            assignments.setClipEnabled(contactId, "characters/frog/clips/croak.ogg", enabled = false)
            assignments.setClipEnabled(contactId, "characters/frog/clips/ribbit.ogg", enabled = false)
            assignments.setClipEnabled(contactId, "characters/frog/clips/ribbit.ogg", enabled = false)
            assignments.setHapticOverride(contactId, """{"mode":"none"}""")

            clock.advance()
            assignments.assign(contactId, PACK, "frog")
            val same = db.assignmentDao().get(contactId)!!
            assertEquals(
                listOf("characters/frog/clips/croak.ogg", "characters/frog/clips/ribbit.ogg"),
                same.disabledClips,
            )
            assertEquals("""{"mode":"none"}""", same.hapticOverride)
            assertEquals(clock.time, same.updatedAt)

            assignments.setClipEnabled(contactId, "characters/frog/clips/croak.ogg", enabled = true)
            assertEquals(listOf("characters/frog/clips/ribbit.ogg"), db.assignmentDao().get(contactId)!!.disabledClips)

            assignments.assign(contactId, PACK, "owl")
            val fresh = db.assignmentDao().get(contactId)!!
            assertEquals(emptyList<String>(), fresh.disabledClips)
            assertNull(fresh.hapticOverride)
        }

    @Test
    fun editsWithoutAnAssignmentAreIgnored() =
        runTest {
            assignments.setClipEnabled(contactId, "a.ogg", enabled = false)
            assignments.setHapticOverride(contactId, """{"mode":"none"}""")
            assertNull(db.assignmentDao().get(contactId))
        }

    @Test
    fun flowsFollowChanges() =
        runTest {
            assignments.observe(contactId).test {
                assertNull(awaitItem())
                assignments.assign(contactId, PACK, "frog")
                assertEquals("frog", awaitItem()?.characterId)
                assignments.unassign(contactId)
                assertNull(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assignments.observeByPack(PACK).test {
                assertEquals(0, awaitItem().size)
                assignments.assign(contactId, PACK, "owl")
                assertEquals(listOf(contactId), awaitItem().map { it.contactId })
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        const val PACK = "app.mauri.demo.critters"
    }
}
