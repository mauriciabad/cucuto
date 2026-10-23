package app.mauri.cucuto.core.haptics.auto

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoHapticFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val ribbit =
        Haptic.Custom(
            listOf(
                HapticEvent.Envelope(
                    40,
                    listOf(ControlPoint(1000, 300, 60), ControlPoint(420, 650, 120), ControlPoint(0, 650, 20)),
                ),
            ),
        )

    private val set =
        AutoHapticSet(
            analyzerVersion = 1,
            maxControlPoints = 16,
            minControlPointMs = 20,
            clips =
                linkedMapOf(
                    "characters/frog/clips/ribbit.ogg" to ribbit,
                    "characters/frog/clips/silence.ogg" to null,
                ),
        )

    @Test
    fun encodesCompactLines() {
        assertEquals(
            "cucuto-auto-haptics\t1\t1\t16\t20\t2\n" +
                "characters/frog/clips/ribbit.ogg\tce40,,1000,300,60,420,650,120,0,650,20\n" +
                "characters/frog/clips/silence.ogg\t-\n",
            AutoHapticFile.encode(set),
        )
    }

    @Test
    fun roundTrips() {
        assertEquals(set, AutoHapticFile.decode(AutoHapticFile.encode(set)))
        val empty = set.copy(clips = emptyMap())
        assertEquals(empty, AutoHapticFile.decode(AutoHapticFile.encode(empty)))
    }

    @Test
    fun ignoresUnknownTrailingFields() {
        val text = AutoHapticFile.encode(set).replace("\t20\t2\n", "\t20\t2\tfuture\n")
        assertEquals(set, AutoHapticFile.decode(text))
    }

    @Test
    fun rejectsBadInput() {
        val good = AutoHapticFile.encode(set)
        val bad =
            listOf(
                "",
                "hello",
                good.replaceFirst("\t1\t1\t", "\t2\t1\t"),
                good.replace("\t20\t2\n", "\t20\t3\n"),
                good.replace("\t-\n", "\tk\n"),
                good.replace("\t-\n", "\tc?\n"),
                good.replace("\t-\n", "\n"),
            )
        for (text in bad) {
            try {
                AutoHapticFile.decode(text)
                fail("Expected a failure for: $text")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun writesAndReadsNextToThePack() {
        val packDir = temp.newFolder("app.mauri.demo.critters", "1.0.0")
        assertNull(AutoHapticFile.read(packDir))
        AutoHapticFile.write(packDir, set)
        assertEquals(set, AutoHapticFile.read(packDir))
        assertEquals(listOf("auto-haptics.txt"), File(packDir, ".cucuto").list()!!.toList())

        File(packDir, AutoHapticFile.RELATIVE_PATH).writeText("garbage")
        assertNull(AutoHapticFile.read(packDir))
    }
}
