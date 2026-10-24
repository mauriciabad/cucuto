package app.mauri.cucuto.xposed.integration

import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.xposed.events.Outcome
import app.mauri.cucuto.xposed.route.AlertRouter
import app.mauri.cucuto.xposed.route.NotificationSnapshot
import app.mauri.cucuto.xposed.route.ShuffleBags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Golden routing tables: what the app published at a given revision, frozen in `src/test/resources/integration/golden/`
 * (recorded by `RoutingIntegrationTest.recordGolden` from its world, with the mirror under [MIRROR_ROOT]).
 *
 * - **Newer modules read older apps' tables.** After an update and a reboot the new module first reads the table the
 *   previous app published, until the new app runs. Every golden must decode and route with today's module.
 * - **The encoding is stable.** Re-encoding a golden gives the same text, so the same table never changes on the wire.
 * - **Older modules read newer apps' tables.** The module of the last release ([ReleasedRoutingReader]) reads every
 *   golden, ignoring what it does not know (ADR 0024, 0037).
 *
 * Goldens are never rewritten. When the format grows, record a new one next to them.
 */
class RoutingGoldenTest {
    private val goldens: Map<String, String> =
        GOLDENS.associateWith { name ->
            checkNotNull(javaClass.getResource("/integration/golden/$name")) { "Missing golden $name" }.readText()
        }

    @Test
    fun everyGoldenRoundTrips() {
        for ((name, encoded) in goldens) {
            assertEquals(name, encoded, RoutingCodec.encode(RoutingCodec.decode(encoded)))
        }
    }

    @Test
    fun theReleasedModuleReadsEveryGolden() {
        for ((name, encoded) in goldens) {
            assertEquals(name, RoutingCodec.decode(encoded).withoutConditions(), ReleasedRoutingReader.decode(encoded))
        }
    }

    @Test
    fun todaysModuleRoutesEveryGolden() {
        for ((name, encoded) in goldens) {
            val table = RoutingCodec.decode(encoded)
            val noon = AlertRouter(ShuffleBags(randomFor = { Random(it.toLong()) }), now = { Pipeline.at(12, 0) })
            val night = AlertRouter(ShuffleBags(randomFor = { Random(it.toLong()) }), now = { Pipeline.at(23, 0) })

            fun clips(
                router: AlertRouter,
                snapshot: NotificationSnapshot,
            ): Set<String> = (1..24).map { router.decide(snapshot, table)!!.clipUri!!.substringAfterLast('/') }.toSet()

            val ana = NotificationSnapshot(WHATSAPP, "individual_chat_defaults_5", shortcutId = "111111111111111@lid")
            assertEquals(name, setOf("ribbit.ogg", "hop.ogg"), clips(noon, ana))
            assertEquals(name, setOf("ribbit.ogg", "croak.ogg"), clips(night, ana))
            assertTrue(name, noon.decide(ana, table)!!.clipUri!!.startsWith("file://$MIRROR_ROOT/"))

            val bo = noon.decide(NotificationSnapshot(WHATSAPP, shortcutId = "222222222222222@lid"), table)!!
            assertTrue(name, bo.haptic is Haptic.Custom)
            assertEquals(
                name,
                Haptic.Off,
                noon.decide(NotificationSnapshot(WHATSAPP, shortcutId = "333333333333333@lid"), table)!!.haptic,
            )

            val family = "120363000000000001@g.us"
            val fromBo =
                noon.decide(
                    NotificationSnapshot(WHATSAPP, "group_chat_defaults_6", family, senderKey = "222222222222222@lid"),
                    table,
                )!!
            assertEquals(name, AlertRouter.DETAIL_PER_SENDER, fromBo.detail)
            assertTrue(name, fromBo.clipUri!!.contains("/cat/"))
            assertTrue(
                name,
                noon
                    .decide(
                        NotificationSnapshot(WHATSAPP, "group_chat_defaults_6", family),
                        table,
                    )!!
                    .clipUri!!
                    .contains("/glass/"),
            )

            val business = noon.decide(NotificationSnapshot(BUSINESS, shortcutId = "111111111111111@lid"), table)!!
            assertTrue(name, business.clipUri!!.contains("/kettle/"))
            val unknown = noon.decide(NotificationSnapshot(WHATSAPP, shortcutId = "999999999999999@lid"), table)!!
            assertEquals(name, Outcome.UNASSIGNED, unknown.outcome)
            assertNotNull(name, table.selfTestClip)
        }
    }

    companion object {
        /** Where `MediaClipMirror` puts the copies on the owner's phone; goldens are recorded with it. */
        const val MIRROR_ROOT = "/storage/emulated/0/Android/media/app.mauri.cucuto/clips"

        private const val WHATSAPP = "com.whatsapp"
        private const val BUSINESS = "com.whatsapp.w4b"

        /** Oldest first. 2026-10-24: everything up to day and night clips (#200). */
        val GOLDENS: List<String> = listOf("2026-10-24-day-night.table")
    }
}

/** [this] as a reader from before clip conditions sees it (ADR 0037). */
internal fun RoutingTable.withoutConditions(): RoutingTable =
    copy(
        conditions = emptyList(),
        voices = voices.map { voice -> voice.copy(clips = voice.clips.map { it.copy(condition = Clip.ALWAYS) }) },
    )
