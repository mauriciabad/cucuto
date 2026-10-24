package app.mauri.cucuto.core.ringtone.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.data.db.PhoneRingtoneRow
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.ringtone.contacts.AndroidContact
import app.mauri.cucuto.core.ringtone.contacts.AndroidContactRingtone
import app.mauri.cucuto.core.ringtone.contacts.AndroidContacts
import app.mauri.cucuto.core.ringtone.render.RenderException
import app.mauri.cucuto.core.ringtone.render.RenderedRing
import app.mauri.cucuto.core.ringtone.render.RingFileRenderer
import app.mauri.cucuto.core.ringtone.store.CharacterRingtones
import app.mauri.cucuto.core.ringtone.store.PhoneRingtoneStore
import app.mauri.cucuto.core.ringtone.store.RingExport
import app.mauri.cucuto.core.ringtone.store.RingtoneCatalog
import app.mauri.cucuto.core.ringtone.store.RingtoneExports
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import app.mauri.cucuto.packspec.RingFiles
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import kotlin.random.Random

/** Contact linking, variant writes, the re-roll, respect for the user's own changes, and undo, with fakes. */
@RunWith(AndroidJUnit4::class)
class RingtoneSyncTest {
    @get:Rule val temp = TemporaryFolder()

    private val store = FakeStore()
    private val contacts = FakeContacts()
    private val exports = FakeExports()
    private val renderer = FakeRenderer()
    private var now = LocalDateTime.parse("2026-10-24T12:00")
    private lateinit var catalog: FakeCatalog

    private fun sync(): RingtoneSync {
        catalog = catalog()
        return RingtoneSync(store, contacts, exports, catalog, renderer, temp.newFolder(), {
            1L
        }, { RuleContext(now) }, Random(3))
    }

    private fun catalog(): FakeCatalog {
        val dir = temp.newFolder()
        File(dir, "day.ogg").writeText("d")
        File(dir, "night.ogg").writeText("n")
        File(dir, "solo.ogg").writeText("s")
        return FakeCatalog(
            mapOf(
                "kk" to
                    CharacterRingtones(
                        "pack",
                        "1.0.0",
                        "kk",
                        "K.K.",
                        dir,
                        listOf(
                            RingtoneVariant(
                                "day.ogg",
                                "Day",
                                listOf("day"),
                                app.mauri.cucuto.packspec
                                    .When(time = JsonPrimitive("day")),
                            ),
                            RingtoneVariant(
                                "night.ogg",
                                "Night",
                                listOf("night"),
                                app.mauri.cucuto.packspec
                                    .When(time = JsonPrimitive("night")),
                            ),
                        ),
                    ),
                "solo" to CharacterRingtones("pack", "1.0.0", "solo", "Solo", dir, listOf(RingtoneVariant("solo.ogg"))),
                "mute" to CharacterRingtones("pack", "1.0.0", "mute", "Mute", dir, emptyList()),
            ),
        )
    }

    private fun row(
        contactId: Long = 1,
        lookupKey: String? = "ada",
        characterId: String = "kk",
        mode: RingtoneMode = RingtoneMode.PACK,
        variant: String? = null,
    ) = PhoneRingtoneRow(contactId, lookupKey, "Ada", "pack", characterId, mode, variant)

    @Test
    fun firstSyncRecordsTheOriginalRingtoneAndWritesTheRuleVariant() =
        runTest {
            contacts.people["ada"] = "content://media/internal/audio/media/7"
            store.rows = listOf(row())
            val result = sync().sync(SyncReason.Changed)

            assertTrue(result.problems.isEmpty())
            val day = exports.uriOf("day")
            assertEquals(day, contacts.people["ada"])
            val write = store.writes.getValue("ada")
            assertEquals("content://media/internal/audio/media/7", write.originalRingtone)
            assertEquals(day, write.writtenUri)
            assertEquals("day.ogg", write.variant)
            // Every variant is exported once, so later switches are only a contact write.
            assertEquals(2, exports.files.size)
            assertEquals(LocalDateTime.parse("2026-10-24T19:00"), result.nextBoundary)
        }

    @Test
    fun ringFileTitlesAndNamesAreReadable() =
        runTest {
            contacts.people["ada"] = null
            store.rows = listOf(row())
            sync().sync(SyncReason.Changed)
            val day = exports.files.values.first { it.displayName.startsWith("kk-day-") }
            assertEquals("K.K. · Day", day.title)
            assertTrue(day.displayName.endsWith(".ogg"))
        }

    @Test
    fun aBoundarySwitchesTheVariantWithoutRendering() =
        runTest {
            contacts.people["ada"] = null
            store.rows = listOf(row())
            val sync = sync()
            sync.sync(SyncReason.Changed)
            val renders = renderer.calls
            now = LocalDateTime.parse("2026-10-24T20:00")
            sync.sync(SyncReason.Changed)
            assertEquals(exports.uriOf("night"), contacts.people["ada"])
            assertEquals(renders, renderer.calls)
            assertNull("the original (default) is kept", store.writes.getValue("ada").originalRingtone)
        }

    @Test
    fun randomRerollsOnlyWhenACallEnds() =
        runTest {
            contacts.people["ada"] = null
            store.rows = listOf(row(mode = RingtoneMode.RANDOM))
            val sync = sync()
            sync.sync(SyncReason.Changed)
            val first = contacts.people["ada"]
            sync.sync(SyncReason.Changed)
            assertEquals(first, contacts.people["ada"])
            sync.sync(SyncReason.CallEnded)
            assertNotEquals(first, contacts.people["ada"])
            sync.sync(SyncReason.CallEnded)
            assertEquals(first, contacts.people["ada"])
        }

    @Test
    fun aCallNeverRendersAndKeepsTheLastPickWhenFilesAreMissing() =
        runTest {
            contacts.people["ada"] = null
            store.rows = listOf(row(mode = RingtoneMode.RANDOM))
            val result = sync().sync(SyncReason.CallEnded)
            assertEquals(0, renderer.calls)
            assertEquals(RingtoneProblem.RenderFailed, result.problems[1])
            assertNull(contacts.people["ada"])
        }

    @Test
    fun aRingtoneChangedInContactsIsLeftAloneUntilTakenOver() =
        runTest {
            contacts.people["ada"] = "content://original"
            store.rows = listOf(row())
            val sync = sync()
            sync.sync(SyncReason.Changed)
            contacts.people["ada"] = "content://users-own-choice"

            val result = sync.sync(SyncReason.Changed)
            assertEquals(RingtoneProblem.ChangedElsewhere, result.problems[1])
            assertEquals("content://users-own-choice", contacts.people["ada"])
            assertTrue(store.writes.getValue("ada").userChanged)

            // Removing it now must not overwrite the user's choice with the old original.
            store.rows = emptyList()
            sync.sync(SyncReason.Changed)
            assertEquals("content://users-own-choice", contacts.people["ada"])
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun takingOverAfterAChangeRestoresTheUsersLatestRingtoneLater() =
        runTest {
            contacts.people["ada"] = "content://original"
            store.rows = listOf(row())
            val sync = sync()
            sync.sync(SyncReason.Changed)
            contacts.people["ada"] = "content://newer"
            sync.sync(SyncReason.Changed)

            sync.takeOver("ada")
            sync.sync(SyncReason.Changed)
            assertEquals(exports.uriOf("day"), contacts.people["ada"])

            store.rows = emptyList()
            sync.sync(SyncReason.Changed)
            assertEquals("content://newer", contacts.people["ada"])
        }

    @Test
    fun removingOrUnlinkingRestoresTheOriginalAndDeletesUnusedFiles() =
        runTest {
            contacts.people["ada"] = "content://original"
            contacts.people["bo"] = null
            store.rows = listOf(row(), row(contactId = 2, lookupKey = "bo", characterId = "solo"))
            val sync = sync()
            sync.sync(SyncReason.Changed)
            assertEquals(3, exports.files.size)

            store.rows = listOf(row(contactId = 2, lookupKey = "bo", characterId = "solo"))
            sync.sync(SyncReason.Changed)
            assertEquals("content://original", contacts.people["ada"])
            assertFalse("ada" in store.writes)
            assertEquals(listOf("solo"), exports.files.values.map { it.displayName.substringBefore('-') })

            store.rows = listOf(row(contactId = 2, lookupKey = null, characterId = "solo"))
            val result = sync.sync(SyncReason.Changed)
            assertNull("bo had the default ringtone", contacts.people["bo"])
            assertEquals(RingtoneProblem.NotLinked, result.problems[2])
            assertTrue(exports.files.isEmpty())
        }

    private fun defaultRow(
        contactId: Long = 1,
        lookupKey: String = "ada",
    ) = PhoneRingtoneRow(contactId, lookupKey, "Ada", null, null, RingtoneMode.PACK, null, RingtoneChoice.ANDROID_DEFAULT)

    @Test
    fun androidDefaultClearsTheContactsOwnRingtoneAndLeaveAsIsGivesItBack() =
        runTest {
            contacts.people["ada"] = "content://original"
            store.rows = listOf(defaultRow())
            val sync = sync()

            assertTrue(sync.sync(SyncReason.Changed).problems.isEmpty())
            assertNull(contacts.people["ada"])
            val write = store.writes.getValue("ada")
            assertEquals("content://original", write.originalRingtone)
            assertEquals(ContactRingtoneWriteEntity.DEFAULT_RINGTONE, write.writtenUri)
            assertTrue(exports.files.isEmpty())

            // A second run changes nothing.
            sync.sync(SyncReason.Changed)
            assertNull(contacts.people["ada"])

            // "Leave as is" rows are not sync rows: the contact gets its own ringtone back.
            store.rows = emptyList()
            sync.sync(SyncReason.Changed)
            assertEquals("content://original", contacts.people["ada"])
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun androidDefaultNeverWritesAContactWithoutARingtoneAndRespectsOneSetLater() =
        runTest {
            contacts.people["ada"] = null
            store.rows = listOf(defaultRow())
            val sync = sync()
            sync.sync(SyncReason.Changed)
            assertEquals(0, contacts.writes)
            assertNull(store.writes.getValue("ada").originalRingtone)

            contacts.people["ada"] = "content://users-own-choice"
            val result = sync.sync(SyncReason.Changed)
            assertEquals(RingtoneProblem.ChangedElsewhere, result.problems[1])
            assertEquals("content://users-own-choice", contacts.people["ada"])

            store.rows = emptyList()
            sync.sync(SyncReason.Changed)
            assertEquals("content://users-own-choice", contacts.people["ada"])
            assertEquals(0, contacts.writes)
        }

    @Test
    fun switchingBetweenACharacterAndTheDefaultKeepsTheOriginal() =
        runTest {
            contacts.people["ada"] = "content://original"
            store.rows = listOf(row())
            val sync = sync()
            sync.sync(SyncReason.Changed)

            store.rows = listOf(defaultRow())
            assertTrue(sync.sync(SyncReason.Changed).problems.isEmpty())
            assertNull(contacts.people["ada"])
            assertTrue("the unused ring files go", exports.files.isEmpty())

            store.rows = listOf(row())
            assertTrue(sync.sync(SyncReason.Changed).problems.isEmpty())
            assertEquals(exports.uriOf("day"), contacts.people["ada"])

            assertTrue(sync.restoreAll())
            assertEquals("content://original", contacts.people["ada"])
        }

    @Test
    fun turningOffGivesAnAndroidDefaultContactItsRingtoneBack() =
        runTest {
            contacts.people["ada"] = "content://original"
            store.rows = listOf(defaultRow())
            val sync = sync()
            sync.sync(SyncReason.Changed)

            assertTrue(sync.restoreAll())
            assertEquals("content://original", contacts.people["ada"])
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun turningOffRestoresEveryContactAndDeletesEveryFile() =
        runTest {
            contacts.people["ada"] = "content://original"
            contacts.people["bo"] = null
            store.rows = listOf(row(), row(contactId = 2, lookupKey = "bo", characterId = "solo"))
            val sync = sync()
            sync.sync(SyncReason.Changed)

            assertTrue(sync.restoreAll())
            assertEquals("content://original", contacts.people["ada"])
            assertNull(contacts.people["bo"])
            assertTrue(store.writes.isEmpty())
            assertTrue(exports.files.isEmpty())
        }

    @Test
    fun aLostUndoRecordNeverTakesCucutosOwnFileAsTheOriginal() =
        runTest {
            contacts.people["ada"] = null
            store.rows = listOf(row())
            val sync = sync()
            sync.sync(SyncReason.Changed)
            store.writes.clear() // app data cleared, ring files and the contact remain
            now = LocalDateTime.parse("2026-10-24T22:00")
            sync.sync(SyncReason.Changed)
            assertNull(store.writes.getValue("ada").originalRingtone)
            assertEquals(exports.uriOf("night"), contacts.people["ada"])
        }

    @Test
    fun problemsAreReportedPerContact() =
        runTest {
            contacts.people["ada"] = null
            store.rows =
                listOf(
                    row(contactId = 1, lookupKey = "gone"),
                    row(contactId = 2, lookupKey = "ada", characterId = "mute"),
                    row(contactId = 3, lookupKey = null),
                )
            val result = sync().sync(SyncReason.Changed)
            assertEquals(RingtoneProblem.ContactMissing, result.problems[1])
            assertEquals(RingtoneProblem.NoRingtones, result.problems[2])
            assertEquals(RingtoneProblem.NotLinked, result.problems[3])
        }

    @Test
    fun withoutPermissionNothingIsTouched() =
        runTest {
            contacts.people["ada"] = "content://original"
            contacts.allowed = false
            store.rows = listOf(row())
            val result = sync().sync(SyncReason.Changed)
            assertEquals(RingtoneProblem.NoPermission, result.problems[1])
            assertEquals("content://original", contacts.people["ada"])
            assertEquals(0, renderer.calls)
        }

    @Test
    fun aFailedRenderIsReportedAndOtherVariantsStillRing() =
        runTest {
            contacts.people["ada"] = null
            renderer.failOn = "day.ogg"
            store.rows = listOf(row())
            val result = sync().sync(SyncReason.Changed)
            assertEquals(RingtoneProblem.RenderFailed, result.problems[1])
            assertEquals(exports.uriOf("night"), contacts.people["ada"])
        }

    @Test
    fun oneAndroidContactRingsForOneCucutoContact() =
        runTest {
            contacts.people["ada"] = null
            store.rows = listOf(row(contactId = 1, characterId = "solo"), row(contactId = 2, characterId = "kk"))
            sync().sync(SyncReason.Changed)
            assertEquals(exports.uriOf("solo"), contacts.people["ada"])
        }

    @Test
    fun anUndoRecordAdoptedFromABackupGivesTheContactItsOwnRingtoneBackLater() =
        runTest {
            // The same phone after a reinstall: Cucuto's old file is on the contact but no longer Cucuto's own
            // MediaStore entry, and the restore adopted the backup's undo record (RingtoneRelinker, ADR 0057).
            contacts.people["ada"] = "content://media/external/audio/media/1042"
            store.writes["ada"] =
                ContactRingtoneWriteEntity(
                    lookupKey = "ada",
                    contactId = 1,
                    originalRingtone = "content://original",
                    writtenUri = "content://media/external/audio/media/1042",
                    variant = null,
                    writtenAt = 1,
                )
            store.rows = listOf(row())
            val sync = sync()
            val result = sync.sync(SyncReason.Changed)
            assertTrue("not mistaken for the user's change", result.problems.isEmpty())
            assertEquals(exports.uriOf("day"), contacts.people["ada"])
            assertEquals("content://original", store.writes.getValue("ada").originalRingtone)

            store.rows = emptyList()
            sync.sync(SyncReason.Changed)
            assertEquals("content://original", contacts.people["ada"])
        }

    private class FakeStore : PhoneRingtoneStore {
        var rows: List<PhoneRingtoneRow> = emptyList()
        val writes = LinkedHashMap<String, ContactRingtoneWriteEntity>()

        override suspend fun rows() = rows

        override suspend fun writes() = writes.values.toList()

        override suspend fun upsertWrite(write: ContactRingtoneWriteEntity) {
            writes[write.lookupKey] = write
        }

        override suspend fun deleteWrite(lookupKey: String) {
            writes.remove(lookupKey)
        }
    }

    private class FakeContacts : AndroidContacts {
        val people = LinkedHashMap<String, String?>()
        var allowed = true

        /** Contact ringtone writes so far. */
        var writes = 0

        override fun canReadAndWrite() = allowed

        override suspend fun find(lookupKey: String) =
            if (lookupKey in
                people
            ) {
                AndroidContactRingtone(lookupKey, lookupKey, people[lookupKey])
            } else {
                null
            }

        override suspend fun setRingtone(
            lookupKey: String,
            uri: String?,
        ): Boolean {
            if (!allowed || lookupKey !in people) return false
            people[lookupKey] = uri
            writes++
            return true
        }

        override suspend fun byPhoneNumber(number: String) = emptyList<AndroidContact>()

        override suspend fun byName(name: String) = emptyList<AndroidContact>()

        override suspend fun resolve(contactUri: String): AndroidContact? = null

        override suspend fun phoneNumbers(lookupKey: String) = emptyList<String>()
    }

    data class Published(
        val displayName: String,
        val title: String,
    )

    private class FakeExports : RingtoneExports {
        val files = LinkedHashMap<String, Published>()
        private var next = 100

        fun uriOf(prefix: String): String =
            files.entries
                .first {
                    it.value.displayName
                        .substringAfter('-')
                        .startsWith(prefix) ||
                        it.value.displayName.startsWith(prefix)
                }.key

        override suspend fun list() = files.map { (uri, p) -> RingExport(uri, p.displayName) }

        override suspend fun publish(
            file: File,
            displayName: String,
            title: String,
            mimeType: String,
        ): String {
            val uri = "content://media/external/audio/media/${next++}"
            files[uri] = Published(displayName, title)
            return uri
        }

        override suspend fun delete(uri: String) {
            files.remove(uri)
        }
    }

    private class FakeRenderer : RingFileRenderer {
        var calls = 0
        var failOn: String? = null

        override fun render(
            source: File,
            loop: app.mauri.cucuto.packspec.LoopRange?,
            fadeInMs: Long,
            workDir: File,
        ): RenderedRing {
            calls++
            if (source.name == failOn) throw RenderException("boom")
            val out = File.createTempFile("ring", ".ogg", workDir)
            return RenderedRing(out, "audio/ogg", "ogg", RingFiles.plan(loop, fadeInMs, 6_000), copied = false)
        }
    }

    private class FakeCatalog(
        private val characters: Map<String, CharacterRingtones>,
    ) : RingtoneCatalog {
        override suspend fun character(
            packId: String,
            characterId: String,
        ) = characters[characterId]
    }
}
