package app.mauri.cucuto.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import app.mauri.cucuto.core.data.FakeClock
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.DecisionLogEntity
import app.mauri.cucuto.core.data.db.DecisionOutcome
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.data.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventAndPackRepositoryTest {
    private lateinit var db: CucutoDatabase
    private val clock = FakeClock()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun decisionLogIsABoundedRing() =
        runTest {
            val log = DecisionLogRepository(db.decisionLogDao())
            repeat(520) { i ->
                log.record(
                    DecisionLogEntity(
                        timestamp = i.toLong(),
                        packageName = "com.whatsapp",
                        outcome = if (i % 2 == 0) DecisionOutcome.SWAPPED else DecisionOutcome.UNASSIGNED,
                        detail = "entry $i",
                    ),
                )
            }
            assertEquals(500, db.decisionLogDao().count())
            log.observeRecent(limit = 3).test {
                assertEquals(listOf("entry 519", "entry 518", "entry 517"), awaitItem().map { it.detail })
                log.clear()
                assertEquals(emptyList<DecisionLogEntity>(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun seenSendersCountSightingsAndOrderByRecency() =
        runTest {
            val senders = SeenSenderRepository(db.seenSenderDao())
            senders.observeRecent().test {
                assertEquals(0, awaitItem().size)

                senders.record("com.whatsapp", "a@s.whatsapp.net", "Ada", isGroup = false, seenAt = 1)
                awaitItem()
                senders.record(
                    "com.whatsapp",
                    "g@g.us",
                    "Family",
                    isGroup = true,
                    seenAt = 2,
                    senderKey = "a@s.whatsapp.net",
                )
                awaitItem()
                senders.record("com.whatsapp", "a@s.whatsapp.net", null, isGroup = false, seenAt = 3)
                val recent = awaitItem()
                assertEquals(listOf("a@s.whatsapp.net", "g@g.us"), recent.map { it.conversationKey })
                val ada = recent.first()
                assertEquals("Ada", ada.displayName)
                assertEquals(2, ada.messageCount)
                assertEquals(1L, ada.firstSeenAt)
                assertEquals(3L, ada.lastSeenAt)
                assertEquals("a@s.whatsapp.net", recent[1].senderKey)

                senders.dismiss("com.whatsapp", "g@g.us")
                assertEquals(listOf("a@s.whatsapp.net"), awaitItem().map { it.conversationKey })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun installedPacksIndex() =
        runTest {
            val packs = InstalledPackRepository(db.installedPackDao())
            val v1 =
                InstalledPackEntity(
                    packId = "app.mauri.demo.critters",
                    version = "1.0.0",
                    name = "Chiptune Critters",
                    path = "/data/packs/app.mauri.demo.critters/1.0.0",
                    sha256 = "0".repeat(64),
                    sizeBytes = 1234,
                    source = PackSource.REPOSITORY,
                    repositoryUrl = "https://cucuto.mauri.app/repository.json",
                    installedAt = 1,
                )
            packs.observeAll().test {
                assertEquals(0, awaitItem().size)
                packs.recordInstalled(v1)
                assertEquals(listOf("1.0.0"), awaitItem().map { it.version })
                packs.recordInstalled(v1.copy(version = "1.1.0", path = "/data/packs/app.mauri.demo.critters/1.1.0"))
                assertEquals(listOf("1.1.0"), awaitItem().map { it.version })
                packs.remove(v1.packId)
                assertEquals(0, awaitItem().size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun repositoriesAreUniqueByUrl() =
        runTest {
            val registry = PackRepositoryRegistry(db.packRepositoryDao(), clock)
            val id = registry.add("https://cucuto.mauri.app/repository.json", "Cucuto", isDefault = true)
            assertTrue(id != null)
            assertNull(registry.add("https://cucuto.mauri.app/repository.json"))
            registry.add("https://example.com/packs/", "Private", RepositoryAuthType.BASIC)

            registry.observeAll().test {
                val all = awaitItem()
                assertEquals(listOf("Cucuto", "Private"), all.map { it.name })
                assertEquals(RepositoryAuthType.BASIC, all[1].authType)

                registry.update(all[1].copy(enabled = false))
                assertEquals(false, awaitItem()[1].enabled)
                registry.remove(all[1].id)
                assertEquals(listOf("Cucuto"), awaitItem().map { it.name })
                cancelAndIgnoreRemainingEvents()
            }
        }
}
