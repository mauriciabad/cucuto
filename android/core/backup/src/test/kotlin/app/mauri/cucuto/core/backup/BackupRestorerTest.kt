package app.mauri.cucuto.core.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mauri.cucuto.core.data.db.AssignmentEntity
import app.mauri.cucuto.core.data.db.ContactEntity
import app.mauri.cucuto.core.data.db.ContactKeyEntity
import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.db.PackRepositoryEntity
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.db.RingtoneRules
import app.mauri.cucuto.core.repo.RepositoryCredentials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupRestorerTest {
    private lateinit var db: CucutoDatabase
    private val clock = FakeClock()
    private val settings = FakeSettings()
    private val credentials = FakeCredentialStore()
    private val restorer by lazy { BackupRestorer(db, settings, credentials, clock, kotlin.random.Random(1)) }

    /** The golden fixture: Ada (WhatsApp key, phone, frog with a haptic) and a per-sender group. */
    private val fixture: BackupDocument =
        BackupJson.decodeFromString(BackupDocument.serializer(), File(fixturesDir, "valid/backup.json").readText())

    private val adaKey = "34600111222@s.whatsapp.net"

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun contact(
        name: String?,
        vararg keys: Pair<String, String>,
        isGroup: Boolean = false,
    ): Long {
        val id =
            db.contactDao().insert(
                ContactEntity(displayName = name, isGroup = isGroup, shuffleSeed = 5, createdAt = 1, updatedAt = 1),
            )
        for ((pkg, value) in keys) {
            db.contactDao().insertKey(
                ContactKeyEntity(
                    contactId = id,
                    packageName = pkg,
                    value = value,
                    kind = ContactKeyKind.classify(value),
                ),
            )
        }
        return id
    }

    @Test
    fun `restoring twice changes nothing the second time`() =
        runTest {
            val first = restorer.restore(fixture, encrypted = false)
            assertEquals(8, first.created)
            assertEquals(
                listOf(
                    VoiceOutcome.APPLIED,
                    VoiceOutcome.APPLIED,
                    VoiceOutcome.APPLIED,
                    VoiceOutcome.NONE,
                    VoiceOutcome.NONE,
                    VoiceOutcome.APPLIED,
                    VoiceOutcome.APPLIED,
                    VoiceOutcome.NONE,
                ),
                first.contacts.map { it.voice },
            )
            assertEquals(6, first.ringtonesApplied)
            val contactsAfterFirst = db.contactDao().observeAll().first()
            val assignmentsAfterFirst = db.assignmentDao().observeAll().first()
            val ringtonesAfterFirst = db.phoneRingtoneDao().observeAll().first()

            clock.time += 60_000
            val second = restorer.restore(fixture, encrypted = false)
            assertEquals(0, second.created)
            assertEquals(
                List(8) { ContactMatch.MESSENGER_KEY },
                second.contacts.map { it.match },
            )
            assertEquals(
                listOf(
                    VoiceOutcome.UNCHANGED,
                    VoiceOutcome.UNCHANGED,
                    VoiceOutcome.UNCHANGED,
                    VoiceOutcome.NONE,
                    VoiceOutcome.NONE,
                    VoiceOutcome.UNCHANGED,
                    VoiceOutcome.UNCHANGED,
                    VoiceOutcome.NONE,
                ),
                second.contacts.map { it.voice },
            )
            assertEquals(
                List(6) { RingtoneOutcome.UNCHANGED },
                second.ringtones.map { it.outcome },
            )
            assertEquals(emptyList<String>(), second.repositoriesAdded)
            assertEquals(contactsAfterFirst, db.contactDao().observeAll().first())
            assertEquals(assignmentsAfterFirst, db.assignmentDao().observeAll().first())
            assertEquals(ringtonesAfterFirst, db.phoneRingtoneDao().observeAll().first())
        }

    @Test
    fun `Android links taken from the backup are reported for matching on this phone, this phone's own are not`() =
        runTest {
            // Bruno exists here with his own Android link; Ada is new.
            val bruno = contact("Bruno", ContactKeyEntity.NO_PACKAGE to "+34600333444")
            db.contactDao().update(
                db
                    .contactDao()
                    .get(
                        bruno,
                    )!!
                    .contact
                    .copy(androidLookupKey = "here-bruno", androidDisplayName = "Bruno"),
            )
            val first = restorer.restore(fixture, encrypted = false)
            val ada = first.contacts.single { it.backupId == "c1" }
            assertEquals(BackupAndroidContact("0r1-ABC", "Ada Lovelace"), ada.androidContact)
            assertEquals("Ada", ada.displayName)
            val restoredBruno = first.contacts.single { it.backupId == "c2" }
            assertEquals(bruno, restoredBruno.contactId)
            assertNull(restoredBruno.androidContact)
            assertEquals(
                "here-bruno",
                db
                    .contactDao()
                    .get(bruno)!!
                    .contact.androidLookupKey,
            )
            assertNull(first.contacts.single { it.backupId == "g1" }.androidContact)

            // A second run finds Ada's link already stored (the app may have matched it again since): not reported.
            val second = restorer.restore(fixture, encrypted = false)
            assertNull(second.contacts.single { it.backupId == "c1" }.androidContact)
        }

    @Test
    fun `an assignment made on this phone is kept and reported until the user overwrites it`() =
        runTest {
            val ada = contact("Ada (new phone)", "com.whatsapp" to adaKey)
            db.assignmentDao().upsert(AssignmentEntity(ada, "com.example.other", "owl", updatedAt = 1))

            val report = restorer.restore(fixture, encrypted = false)
            val conflict = report.conflicts.single()
            assertEquals("c1", conflict.backupId)
            assertEquals(ada, conflict.contactId)
            assertEquals("owl", conflict.current.characterId)
            assertEquals("frog", conflict.backup.characterId)
            assertEquals("owl", db.assignmentDao().get(ada)!!.characterId)
            // The name set on this phone stays; missing data (the phone number, the Android link) is added.
            val restored = db.contactDao().get(ada)!!
            assertEquals("Ada (new phone)", restored.contact.displayName)
            assertEquals("0r1-ABC", restored.contact.androidLookupKey)
            assertTrue(restored.keys.any { it.value == "+34600111222" })

            val overwritten = restorer.restore(fixture, encrypted = false, RestoreOptions(overwrite = setOf("c1")))
            assertEquals(VoiceOutcome.APPLIED, overwritten.contacts.first().voice)
            assertEquals("frog", db.assignmentDao().get(ada)!!.characterId)
            assertEquals(emptyList<AssignmentConflict>(), restorer.restore(fixture, encrypted = false).conflicts)
        }

    @Test
    fun `contacts match by phone number, and keys of other contacts are never moved`() =
        runTest {
            val byPhone = contact("Ada", ContactKeyEntity.NO_PACKAGE to "+34600111222")
            val other = contact("Someone", "com.whatsapp.w4b" to "120363000000000000@g.us")

            val report = restorer.restore(fixture, encrypted = false)
            val ada = report.contacts.single { it.backupId == "c1" }
            assertEquals(ContactMatch.PHONE_NUMBER, ada.match)
            assertEquals(byPhone, ada.contactId)
            assertEquals("frog", db.assignmentDao().get(byPhone)!!.characterId)
            assertTrue(
                db
                    .contactDao()
                    .get(byPhone)!!
                    .keys
                    .any { it.value == adaKey },
            )

            val group = report.contacts.single { it.backupId == "g1" }
            assertEquals(other, group.contactId)
            assertEquals(ContactMatch.MESSENGER_KEY, group.match)
            // Not a group on this phone: the group policy does not apply.
            assertEquals(VoiceOutcome.NONE, group.voice)
        }

    @Test
    fun `a key owned by another contact is reported, not stolen`() =
        runTest {
            val ada = contact("Ada", "com.whatsapp" to adaKey)
            val impostor = contact("Phone owner", ContactKeyEntity.NO_PACKAGE to "+34600111222")
            val outcome = restorer.restore(fixture, encrypted = false).contacts.first()
            assertEquals(ada, outcome.contactId)
            assertEquals(listOf("+34600111222"), outcome.keysOwnedElsewhere)
            assertEquals(impostor, db.contactDao().findKey(ContactKeyEntity.NO_PACKAGE, "+34600111222")!!.contactId)
        }

    @Test
    fun `a group policy set on this phone is a conflict, an untouched one is filled`() =
        runTest {
            val group = contact("Family", "com.whatsapp.w4b" to "120363000000000000@g.us", isGroup = true)
            val first = restorer.restore(fixture, encrypted = false).contacts.single { it.backupId == "g1" }
            assertEquals(VoiceOutcome.APPLIED, first.voice)
            assertEquals(
                GroupPolicy.PER_SENDER,
                db
                    .contactDao()
                    .get(group)!!
                    .contact.groupPolicy,
            )

            // The user switches back to one voice for the group and assigns one: the backup must not undo that.
            db.contactDao().update(
                db
                    .contactDao()
                    .get(group)!!
                    .contact
                    .copy(groupPolicy = GroupPolicy.GROUP),
            )
            db.assignmentDao().upsert(AssignmentEntity(group, "com.example.other", "owl", updatedAt = 1))
            val report = restorer.restore(fixture, encrypted = false)
            assertEquals(
                GroupPolicy.PER_SENDER,
                report.conflicts
                    .single()
                    .backup.groupPolicy,
            )
            assertEquals(
                GroupPolicy.GROUP,
                db
                    .contactDao()
                    .get(group)!!
                    .contact.groupPolicy,
            )
        }

    @Test
    fun `repositories on this phone keep their settings and credentials`() =
        runTest {
            val url = "https://packs.example.com/private/"
            db.packRepositoryDao().insert(PackRepositoryEntity(url = url, name = "Mine", enabled = false, addedAt = 1))
            credentials.put(url, RepositoryCredentials.Basic("me", "mine"))
            val withSecret =
                fixture.copy(
                    repositories =
                        fixture.repositories.map {
                            if (it.url ==
                                url
                            ) {
                                it.copy(auth = it.auth.copy(credentials = BackupCredentials("ana", "s3cret")))
                            } else {
                                it
                            }
                        },
                )
            val report = restorer.restore(withSecret, encrypted = true)
            assertEquals(listOf("https://cucuto.mauri.app/repo/repository.json"), report.repositoriesAdded)
            assertEquals("Mine", db.packRepositoryDao().getByUrl(url)!!.name)
            assertEquals(RepositoryCredentials.Basic("me", "mine"), credentials.entries[url])
            assertEquals(emptyList<String>(), report.credentialsRestored)

            credentials.entries.clear()
            val plain = restorer.restore(withSecret, encrypted = false)
            assertEquals(listOf(url), plain.credentialsSkipped)
            assertNull(credentials.entries[url])
            val encrypted = restorer.restore(withSecret, encrypted = true)
            assertEquals(listOf(url), encrypted.credentialsRestored)

            val invalid =
                withSecret.copy(
                    repositories =
                        withSecret.repositories.map {
                            it.copy(auth = it.auth.copy(credentials = BackupCredentials("a:b", "x")))
                        },
                )
            credentials.entries.clear()
            val skipped = restorer.restore(invalid, encrypted = true)
            assertTrue(url in skipped.credentialsSkipped)
        }

    @Test
    fun `phone ringtones come back with their character, rule and the old phone's Android contact`() =
        runTest {
            val report = restorer.restore(fixture, encrypted = false)
            val ada = report.ringtones.single { it.backupId == "c1" }
            assertEquals(RingtoneOutcome.APPLIED, ada.outcome)
            assertEquals("0r1-ABC", ada.androidContact!!.lookupKey)
            assertNull(ada.undo)
            val adaRow = db.phoneRingtoneDao().get(ada.contactId)!!
            // Mixed: the ringtone is another character than the voice (frog).
            assertEquals("com.example.private.songs.day" to "bubblegum", adaRow.packId to adaRow.characterId)
            assertEquals(RingtoneMode.FIXED, adaRow.mode)
            assertEquals("characters/bubblegum/ringtones/live.ogg", adaRow.variant)

            val bruno = report.ringtones.single { it.backupId == "c2" }
            val brunoRow = db.phoneRingtoneDao().get(bruno.contactId)!!
            // Follows the voice: no character of its own, so a later voice change changes the ringtone too.
            assertNull(brunoRow.packId)
            assertNull(brunoRow.characterId)
            assertTrue(brunoRow.followsVoice)
            assertEquals(RingtoneMode.TIME, brunoRow.mode)
            assertNull(brunoRow.variant)
            assertEquals("content://media/external/audio/media/1042", bruno.undo!!.writtenUri)
            // The undo record is the app's to seed (it needs the contacts permission), never the restorer's.
            assertEquals(emptyList<Any>(), db.phoneRingtoneDao().writes())
            assertFalse("picked by the user", adaRow.followsVoice)

            // Kept (ADR 0063): the voice (a bird) has no ringtones, so the song that rang stays and still follows.
            val carla = db.phoneRingtoneDao().get(report.ringtones.single { it.backupId == "c3" }.contactId)!!
            assertEquals("com.example.private.songs.day" to "bubblegum", carla.packId to carla.characterId)
            assertTrue(carla.followsVoice)

            // A ringtone and no message voice: the two are independent.
            val dani = report.contacts.single { it.backupId == "c4" }
            assertNull(db.assignmentDao().get(dani.contactId))
            val daniRow = db.phoneRingtoneDao().get(dani.contactId)!!
            assertEquals("bubblegum", daniRow.characterId)
            assertFalse(daniRow.followsVoice)
            assertEquals(RingtoneMode.RANDOM, daniRow.mode)

            // Her own rules (ADR 0074): Radio unless it is a spring or summer day, in the backup's order.
            val eva = db.phoneRingtoneDao().get(report.contacts.single { it.backupId == "c5" }.contactId)!!
            assertEquals(RingtoneMode.CUSTOM, eva.mode)
            assertEquals(
                listOf("characters/bubblegum/ringtones/radio.ogg", "characters/bubblegum/ringtones/live.ogg"),
                RingtoneRules.decode(eva.rules).map { it.variant },
            )
            assertEquals(
                """{"time":"day","season":["spring","summer"]}""",
                RingtoneRules.decode(eva.rules)[1].condition.toString(),
            )
        }

    @Test
    fun `android default and leave as is come back as choices, and only the default looks for its contact`() =
        runTest {
            val report = restorer.restore(fixture, encrypted = false)

            val elena = report.ringtones.single { it.backupId == "c6" }
            assertEquals(RingtoneOutcome.APPLIED, elena.outcome)
            assertEquals("0r6-PQR", elena.androidContact!!.lookupKey)
            val elenaRow = db.phoneRingtoneDao().get(elena.contactId)!!
            assertEquals(RingtoneChoice.ANDROID_DEFAULT, elenaRow.choice)
            assertNull(elenaRow.packId)
            assertFalse(elenaRow.followsVoice)

            assertTrue(report.ringtones.none { it.backupId == "c7" })
            val fede = report.contacts.single { it.backupId == "c7" }
            assertEquals(RingtoneChoice.UNCHANGED, db.phoneRingtoneDao().get(fede.contactId)!!.choice)

            val again = restorer.restore(fixture, encrypted = false)
            assertEquals(RingtoneOutcome.UNCHANGED, again.ringtones.single { it.backupId == "c6" }.outcome)
        }

    @Test
    fun `a phone ringtone set on this phone is kept until the user overwrites the contact`() =
        runTest {
            val ada = contact("Ada", "com.whatsapp" to adaKey)
            val mine = PhoneRingtoneEntity(ada, "com.example.other", "owl", RingtoneMode.RANDOM, updatedAt = 1)
            db.phoneRingtoneDao().upsert(mine)

            val kept = restorer.restore(fixture, encrypted = false)
            assertEquals(RingtoneOutcome.KEPT, kept.ringtones.single { it.backupId == "c1" }.outcome)
            assertEquals(1, kept.ringtonesKept)
            assertEquals(mine, db.phoneRingtoneDao().get(ada))

            val overwritten = restorer.restore(fixture, encrypted = false, RestoreOptions(overwrite = setOf("c1")))
            assertEquals(RingtoneOutcome.APPLIED, overwritten.ringtones.single { it.backupId == "c1" }.outcome)
            assertEquals("bubblegum", db.phoneRingtoneDao().get(ada)!!.characterId)
        }

    @Test
    fun `a contact without phone ringtone on this phone gets the backup's, even when its voice is a conflict`() =
        runTest {
            val ada = contact("Ada", "com.whatsapp" to adaKey)
            db.assignmentDao().upsert(AssignmentEntity(ada, "com.example.other", "owl", updatedAt = 1))
            val report = restorer.restore(fixture, encrypted = false)
            assertEquals("c1", report.conflicts.single().backupId)
            assertEquals(RingtoneOutcome.APPLIED, report.ringtones.single { it.backupId == "c1" }.outcome)
            assertEquals("bubblegum", db.phoneRingtoneDao().get(ada)!!.characterId)
        }

    @Test
    fun `known settings are applied and unknown ones ignored`() =
        runTest {
            settings.enabled = true
            val document =
                fixture.copy(
                    settings =
                        JsonObject(
                            mapOf(
                                "enabled" to JsonPrimitive(false),
                                "future" to JsonPrimitive(1),
                            ),
                        ),
                )
            val report = restorer.restore(document, encrypted = false)
            assertEquals(listOf("enabled"), report.settings.applied)
            assertEquals(listOf("future"), report.settings.ignored)
            assertEquals(false, settings.enabled)
        }
}
