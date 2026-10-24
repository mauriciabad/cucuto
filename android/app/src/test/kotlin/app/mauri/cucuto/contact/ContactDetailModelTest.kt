package app.mauri.cucuto.contact

import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.DecisionLogEntity
import app.mauri.cucuto.core.data.db.DecisionOutcome
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.Moment
import app.mauri.cucuto.diagnostics.SpacingGate
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.BEA
import app.mauri.cucuto.testing.TestData.CRITTERS
import app.mauri.cucuto.testing.TestData.WA
import app.mauri.cucuto.testing.TestData.chat
import app.mauri.cucuto.testing.TestData.packs
import app.mauri.cucuto.xposed.route.Targets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContactDetailModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val frog =
        packs
            .first()
            .characters
            .first()
            .withPaths()

    @Test
    fun findsTheChatsCharacterAndItsPack() {
        val (character, pack) = characterOf(chat(ADA, "Ada", ChatVoice(CRITTERS, "owl")), packs)!!
        assertEquals("Hoot the owl", character.name)
        assertEquals("Chiptune critters", pack)
        assertNull(characterOf(chat(ADA, "Ada", ChatVoice("gone.pack", "owl")), packs))
        assertNull(characterOf(chat(ADA, "Ada"), packs))
    }

    @Test
    fun clipRowsFollowTheTogglesAndKeepTheLastClipOn() {
        val rows = clipRows(frog, disabled = emptyList())
        assertEquals(listOf("0", "1"), rows.map { it.name })
        assertTrue(rows.all { it.enabled && it.canToggle })

        val oneLeft = clipRows(frog, disabled = listOf(frog.clipPaths[0]))
        assertFalse(oneLeft[0].enabled)
        assertTrue(oneLeft[0].canToggle)
        assertTrue(oneLeft[1].enabled)
        assertFalse(oneLeft[1].canToggle)

        // Unknown pack paths (previews) show no toggles rather than wrong ones.
        assertEquals(emptyList<ClipRow>(), clipRows(frog.copy(clipPaths = emptyList()), emptyList()))
        assertEquals(emptyList<ClipRow>(), clipRows(null, emptyList()))
    }

    @Test
    fun clipRowsCarryEachClipsMoment() {
        val rare = Moment(oneIn = 25)
        val rows = clipRows(frog.copy(clipMoments = listOf(Moment.NONE, rare)), disabled = emptyList())
        assertEquals(listOf(Moment.NONE, rare), rows.map { it.moment })
        // Unknown moments (previews) are ordinary clips.
        assertTrue(clipRows(frog, emptyList()).all { it.moment == Moment.NONE })
    }

    @Test
    fun hapticSourceReadsTheOverrideLikeTheRoutingTable() {
        assertEquals(HapticSource.Character, hapticSource(null))
        assertEquals(HapticSource.Character, hapticSource("not json"))
        assertEquals(HapticSource.FollowVoice, hapticSource("""{"mode":"auto"}"""))
        assertEquals(HapticSource.Off, hapticSource("""{"mode":"none"}"""))
        assertEquals(
            HapticSource.Custom,
            hapticSource("""{"mode":"custom","events":[{"at":0,"preset":"CLICK","scale":0.5}]}"""),
        )
    }

    private fun entry(
        id: Long,
        key: String?,
        pkg: String = WA,
        contactId: Long? = null,
        outcome: DecisionOutcome = DecisionOutcome.SWAPPED,
        detail: String? = null,
        clip: String? = "content://app.mauri.cucuto.clips/$CRITTERS/frog/characters/frog/clips/ribbit.ogg",
    ) = DecisionLogEntity(
        id = id,
        timestamp = 1_000L * id,
        packageName = pkg,
        conversationKey = key,
        contactId = contactId,
        outcome = outcome,
        clipUri = clip,
        detail = detail,
    )

    @Test
    fun decisionsForChatMatchItsKeysItsContactAndItsSimulations() {
        val ada =
            chat(
                ADA,
                "Ada",
                ChatVoice(CRITTERS, "frog"),
                contactId = 7,
            ).copy(keys = listOf(ADA, ADA.copy(conversationKey = "34600000000@s.whatsapp.net")))
        val log =
            listOf(
                entry(9, ADA.conversationKey, pkg = Targets.SELF_PACKAGE, contactId = 7, detail = "simulated"),
                entry(8, BEA.conversationKey, contactId = 8),
                entry(7, "34600000000@s.whatsapp.net", outcome = DecisionOutcome.UNASSIGNED, clip = null),
                entry(6, "120300000000001@g.us", contactId = 7, detail = "per-sender"),
                entry(5, ADA.conversationKey, pkg = "com.whatsapp.w4b"),
                entry(4, BEA.conversationKey, pkg = Targets.SELF_PACKAGE, detail = "simulated"),
                entry(3, "cucuto:self-test", pkg = Targets.SELF_PACKAGE, detail = "self-test"),
            )

        val rows = decisionsForChat(log, ADA, ada)

        assertEquals(listOf(9L, 7L, 6L), rows.map { it.id })
        assertTrue(rows[0].simulated)
        assertEquals("ribbit", rows[0].clipName)
        assertNull(rows[1].clipName)
        assertTrue(rows[2].perSender)
        assertFalse(rows[2].simulated)
    }

    @Test
    fun decisionsForAnUnknownChatUseItsKeyAndTheLimit() {
        val log = (1L..20L).map { entry(it, ADA.conversationKey) }.reversed()
        val rows = decisionsForChat(log, ADA, chat = null)
        assertEquals(RECENT_DECISIONS, rows.size)
        assertEquals(20L, rows.first().id)
    }

    @Test
    fun theReportIsTheNewestSimulationSinceItWasSent() {
        val rows =
            listOf(
                DecisionRow(3, 10_000, DecisionOutcome.SWAPPED, "a", simulated = false, perSender = false),
                DecisionRow(2, 9_000, DecisionOutcome.SWAPPED, "b", simulated = true, perSender = false),
                DecisionRow(1, 1_000, DecisionOutcome.SWAPPED, "c", simulated = true, perSender = false),
            )
        assertEquals(2L, reportFor(rows, sentAtMillis = 10_500)?.id)
        assertNull(reportFor(rows, sentAtMillis = 20_000))
    }

    @Test
    fun clipUrisResolveToInstalledFilesOnly() {
        val root = temp.newFolder("packs")
        val pack = File(root, "$CRITTERS/1.0.0/characters/frog/clips").apply { mkdirs() }
        File(root, "$CRITTERS/active").writeText("1.0.0\n")
        val clip = File(pack, "ribbit.ogg").apply { writeText("ogg") }

        assertEquals(
            clip.canonicalFile,
            clipFile("content://app.mauri.cucuto.clips/$CRITTERS/frog/characters/frog/clips/ribbit.ogg", root),
        )
        assertEquals(clip, clipFile(clip.toURI().toString(), root))
        assertNull(clipFile("content://app.mauri.cucuto.clips/$CRITTERS/frog/characters/frog/clips/gone.ogg", root))
        assertNull(clipFile("content://other.authority/$CRITTERS/frog/characters/frog/clips/ribbit.ogg", root))
        assertNull(clipFile("content://app.mauri.cucuto.clips/$CRITTERS/frog/../../../etc/passwd.ogg", root))
        assertNull(clipFile("file:///nowhere/ribbit.ogg", root))
        assertNull(clipFile("https://example.com/ribbit.ogg", root))
        assertNull(clipFile("not a uri", root))
    }

    @Test
    fun clipNamesAndHapticPlayback() {
        assertEquals("ribbit", clipName("characters/frog/clips/ribbit.ogg"))
        assertEquals("ribbit", clipName("content://app.mauri.cucuto.clips/p.q/frog/clips/ribbit.ogg"))
        val custom = Haptic.Custom(listOf(HapticEvent.Preset(0, HapticPreset.CLICK)))
        assertEquals(HapticPlayback.Custom, custom.playback())
        assertEquals(HapticPlayback.Off, Haptic.Off.playback())
        assertEquals(HapticPlayback.WhatsApp, Haptic.Keep.playback())
        assertEquals(HapticPlayback.WhatsApp, null.playback())
    }

    @Test
    fun ownAlertsAreSpacedSoAndroidNeverMutesThem() {
        val gate = SpacingGate(spacingMillis = 12_000)
        assertNull(gate.tryAcquire(100_000))
        assertEquals(12, gate.tryAcquire(100_001))
        assertEquals(1, gate.tryAcquire(111_500))
        assertNull(gate.tryAcquire(112_000))
    }

    private fun app.mauri.cucuto.core.data.voices.VoiceCharacter.withPaths() =
        copy(clipPaths = clips.map { it.path.substringAfter("/1.0.0/") })
}
