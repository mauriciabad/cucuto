package app.mauri.cucuto.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the explainer's timeline against the brief (docs/explainer-video.md): length, captions, sounds from the CC0
 * demo packs and the brand only, and that the committed caption files match the timeline.
 * `./gradlew :video:writeExplainerCaptions` rewrites them instead of comparing.
 */
class TimelineTest {
    private val timeline = ExplainerVideo
    private val root = Repository.root

    @Test
    fun lastsAtMostOneMinute() {
        assertEquals(58.0, timeline.duration, 1e-9)
        assertTrue(timeline.duration <= 60.0)
    }

    @Test
    fun captionsFollowTheBrief() {
        val ids = timeline.captions().map { it.id }
        assertEquals("cue IDs are unique", ids.size, ids.toSet().size)
        for (placed in timeline.placed) {
            for (caption in placed.shot.captions) {
                val where = "${placed.shot.id}/${caption.id}"
                assertTrue(
                    "$where starts inside its shot",
                    caption.at >= 0 && caption.until <= placed.shot.duration + 1e-9,
                )
                val length = caption.until - caption.at
                assertTrue("$where is on screen 0.8–6 s, not $length s", length in 0.8 - 1e-9..6.0 + 1e-9)
                for (text in listOf(caption.en, caption.es)) {
                    val lines = text.split('\n')
                    assertTrue("$where has at most 2 lines", lines.size <= 2)
                    lines.forEach { assertTrue("$where: \"$it\" is over 42 characters", it.length <= 42) }
                }
                caption.spoken?.let { assertTrue("$where speaks something", it.isNotBlank() && '\n' !in it) }
            }
        }
        val voice = timeline.captions().filter { it.isVoice }
        voice.zipWithNext().forEach { (a, b) ->
            assertTrue("${a.id} ends before ${b.id} starts", a.until <= b.at + 1e-9)
        }
    }

    @Test
    fun soundsAreDemoPackClipsAndBrandSounds() {
        for (placed in timeline.placed) {
            for (cue in placed.shot.cues) {
                assertTrue(
                    "${cue.file}: only packs/ and brand/sounds/",
                    cue.file.startsWith("packs/") || cue.file.startsWith("brand/sounds/"),
                )
                assertTrue("${cue.file} exists", File(root, cue.file).isFile)
                assertTrue("${cue.file} plays inside ${placed.shot.id}", cue.at >= 0 && cue.at < placed.shot.duration)
            }
            placed.shot.vibrations.forEach { assertTrue(it >= 0 && it < placed.shot.duration) }
        }
        for (character in Cast.all) assertTrue("${character.sprite} exists", File(root, character.sprite).isFile)
    }

    @Test
    fun captionFilesMatchTheTimeline() {
        val write = System.getProperty(WRITE_CAPTIONS) == "true"
        for (language in listOf("en", "es")) {
            val file = File(root, "web/static/video/explainer.$language.vtt")
            val expected = Exports.vtt(timeline, language)
            if (write) {
                file.writeText(expected)
            } else {
                assertEquals(
                    "$file is out of date: run ./gradlew :video:writeExplainerCaptions",
                    expected,
                    file.readText(),
                )
            }
        }
    }

    companion object {
        const val WRITE_CAPTIONS = "cucuto.video.writeCaptions"
    }
}
