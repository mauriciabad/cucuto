package app.mauri.cucuto.core.data.chats

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.FakeClock
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.inMemoryDatabase
import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.SeenSenderRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The message voice and the phone ringtone are independent choices (ADR 0063, #245): what happens to the ringtone when
 * the chat directory changes the voice. Made-up keys and packs only.
 */
@RunWith(AndroidJUnit4::class)
class PhoneRingtoneFollowTest {
    private lateinit var db: CucutoDatabase
    private lateinit var directory: RoomChatDirectory
    private val clock = FakeClock()

    /** Pikachu and Raichu speak and ring; Tweet and Chirp only speak; the K.K. song only rings. */
    private val ringing = setOf(PIKACHU, RAICHU, KK)
    private val ada = ChatKey(WA, ADA)

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        directory =
            RoomChatDirectory(
                ContactRepository(db, db.contactDao(), clock),
                AssignmentRepository(db, db.assignmentDao(), clock),
                SeenSenderRepository(db.seenSenderDao()),
                PhoneRingtoneFollow(db.phoneRingtoneDao(), { it in ringing }, clock),
            )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun assign(voice: ChatVoice) = directory.assign(ada, voice.packId, voice.characterId)

    private suspend fun row(contactId: Long) = db.phoneRingtoneDao().get(contactId)

    /** The character the sync rings with (own, else the voice's). */
    private suspend fun ringsWith(contactId: Long): ChatVoice? =
        db
            .phoneRingtoneDao()
            .syncRows()
            .firstOrNull { it.contactId == contactId }
            ?.let { r -> r.packId?.let { ChatVoice(it, r.characterId!!) } }

    private suspend fun follow(contactId: Long) =
        db.phoneRingtoneDao().upsert(PhoneRingtoneEntity(contactId = contactId, updatedAt = 1))

    @Test
    fun aRingtoneNeverPickedFollowsAVoiceWithRingtones() =
        runTest {
            val id = assign(PIKACHU)
            follow(id)
            assertEquals(PIKACHU, ringsWith(id))

            assign(RAICHU)

            assertEquals(RAICHU, ringsWith(id))
            assertTrue(row(id)!!.followsVoice)
            assertNull(row(id)!!.packId)
        }

    @Test
    fun aVoiceWithoutRingtonesKeepsTheCharacterThatRang() =
        runTest {
            val id = assign(PIKACHU)
            follow(id)

            assign(TWEET)

            assertEquals(PIKACHU, ringsWith(id))
            assertEquals(PIKACHU.packId, row(id)!!.packId)
            assertTrue("still follows the voice", row(id)!!.followsVoice)
        }

    @Test
    fun aKeptRingtoneFollowsAgainOnceTheVoiceHasRingtones() =
        runTest {
            val id = assign(PIKACHU)
            follow(id)
            assign(TWEET)

            assign(RAICHU)

            assertEquals(RAICHU, ringsWith(id))
            assertNull(row(id)!!.packId)
        }

    @Test
    fun aRingtoneNotYetStoredIsKeptWhenTheVoiceStopsRinging() =
        runTest {
            // Not linked yet: contact detail already shows Pikachu under Phone calls, so it stays there.
            val id = assign(PIKACHU)
            assertNull(row(id))

            assign(TWEET)

            assertEquals(PIKACHU, ringsWith(id))
            assertTrue(row(id)!!.followsVoice)
        }

    @Test
    fun withNothingThatRingsTheRingtoneStaysEmpty() =
        runTest {
            val id = assign(TWEET)
            assign(TWEET_FRIEND)
            assertNull(row(id))

            follow(id)
            assign(TWEET)
            // No character of its own, and the voice has no ringtones: the sync finds nothing to ring.
            assertNull(row(id)!!.packId)
        }

    @Test
    fun aPickedRingtoneNeverChangesWithTheVoice() =
        runTest {
            val id = assign(TWEET)
            db.phoneRingtoneDao().upsert(
                PhoneRingtoneEntity(
                    contactId = id,
                    packId = KK.packId,
                    characterId = KK.characterId,
                    mode = RingtoneMode.FIXED,
                    variant = "songs/bubblegum/live.ogg",
                    updatedAt = 1,
                ),
            )
            val picked = row(id)!!
            assertFalse(picked.followsVoice)

            assign(PIKACHU)
            assertEquals(picked, row(id))
            directory.unassign(ada)
            assertEquals(picked, row(id))
            assertEquals(KK, ringsWith(id))
        }

    @Test
    fun removingTheVoiceKeepsTheRingtone() =
        runTest {
            val id = assign(PIKACHU)
            follow(id)

            directory.unassign(ada)

            assertEquals(PIKACHU, ringsWith(id))
        }

    @Test
    fun reassigningTheSameCharacterChangesNothing() =
        runTest {
            val id = assign(PIKACHU)
            follow(id)
            val before = row(id)

            assign(PIKACHU)

            assertEquals(before, row(id))
        }

    @Test
    fun aChatCanGetAContactBeforeItHasAVoice() =
        runTest {
            val id = directory.ensureContact(ada)

            assertEquals(id, directory.ensureContact(ada))
            assertEquals(id, assign(TWEET))
        }

    private companion object {
        const val WA = "com.whatsapp"
        const val ADA = "34600000001@s.whatsapp.net"
        val PIKACHU = ChatVoice("com.example.creatures", "pikachu")
        val TWEET = ChatVoice("com.example.creatures", "tweet")
        val TWEET_FRIEND = ChatVoice("com.example.creatures", "chirp")
        val KK = ChatVoice("com.example.songs", "bubblegum")

        /** Another character with clips and ringtones. */
        val RAICHU = PIKACHU.copy(characterId = "raichu")
    }
}
