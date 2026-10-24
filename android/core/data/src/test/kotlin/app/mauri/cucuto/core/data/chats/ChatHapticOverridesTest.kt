package app.mauri.cucuto.core.data.chats

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.FakeClock
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.inMemoryDatabase
import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.SeenSenderRepository
import app.mauri.cucuto.core.data.routing.HapticOverride
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Made-up keys only. */
@RunWith(AndroidJUnit4::class)
class ChatHapticOverridesTest {
    private lateinit var db: CucutoDatabase
    private lateinit var assignments: AssignmentRepository
    private lateinit var directory: RoomChatDirectory
    private lateinit var overrides: RoomChatHapticOverrides
    private val clock = FakeClock()
    private val ada = ChatKey(WA, ADA)

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        val contacts = ContactRepository(db, db.contactDao(), clock)
        assignments = AssignmentRepository(db, db.assignmentDao(), clock)
        directory = RoomChatDirectory(contacts, assignments, SeenSenderRepository(db.seenSenderDao()))
        overrides = RoomChatHapticOverrides(contacts, assignments)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aChatWithoutAVoiceHasNoOverride() =
        runTest {
            assertNull(overrides.observe(ada).first())
            assertFalse(overrides.set(ada, HapticOverride.Off))
            assertNull(overrides.observe(ada).first())
        }

    @Test
    fun overridesAreStoredOnTheAssignmentAsPackJson() =
        runTest {
            val contactId = directory.assign(ada, PACK, "frog")
            assertEquals(
                ChatHaptic(contactId, ChatVoice(PACK, "frog"), HapticOverride.PackDefault),
                overrides.observe(ada).first(),
            )

            val custom = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.CLICK, 800)))
            for (choice in listOf(HapticOverride.Auto, HapticOverride.Off, HapticOverride.Custom(custom))) {
                assertTrue(overrides.set(ada, choice))
                assertEquals(choice, overrides.observe(ada).first()?.override)
            }
            assertEquals(
                """{"mode":"custom","events":[{"at":0,"preset":"CLICK","scale":0.8}]}""",
                assignments.observe(contactId).first()?.hapticOverride,
            )

            assertTrue(overrides.set(ada, HapticOverride.PackDefault))
            assertNull(assignments.observe(contactId).first()?.hapticOverride)
        }

    @Test
    fun theOverrideBelongsToTheVoice() =
        runTest {
            directory.assign(ada, PACK, "frog")
            overrides.set(ada, HapticOverride.Off)
            // A different character starts fresh; removing the voice removes the override.
            directory.assign(ada, PACK, "owl")
            assertEquals(HapticOverride.PackDefault, overrides.observe(ada).first()?.override)
            overrides.set(ada, HapticOverride.Auto)
            directory.unassign(ada)
            assertNull(overrides.observe(ada).first())
        }

    private companion object {
        const val WA = "com.whatsapp"
        const val ADA = "100000000000001@lid"
        const val PACK = "app.mauri.demo.critters"
    }
}
