package app.mauri.cucuto.video

import java.util.Locale
import kotlin.math.roundToLong

/**
 * What the timeline hands to the rest of the pipeline: the caption files the website serves
 * (`web/static/video/explainer.{en,es}.vtt`), the voice-over script for `tools/video/voice.main.kts` and the sound
 * cues for `tools/video/explainer.main.kts`. All three come from [Timeline], so they can't drift apart.
 */
internal object Exports {
    /** The WebVTT captions in [language] (`en` or `es`). Both files share cue IDs and times. */
    fun vtt(
        timeline: Timeline,
        language: String,
    ): String =
        buildString {
            append("WEBVTT\n\n")
            append("NOTE\n")
            append("Captions of the Cucuto explainer video (docs/explainer-video.md), generated from its timeline in\n")
            append(
                "android/video (Explainer.kt). Edit the timeline, then run ./gradlew :video:writeExplainerCaptions.\n",
            )
            append("Cue IDs (s<shot>-<what>) and times are the same in explainer.en.vtt and explainer.es.vtt.\n")
            for (caption in timeline.captions()) {
                append('\n')
                append(caption.id).append('\n')
                append(timestamp(caption.at)).append(" --> ").append(timestamp(caption.until)).append('\n')
                append(if (language == "es") caption.es else caption.en).append('\n')
            }
        }

    /** The voice-over script: one line per voice line, `id`, start and end (s), and the text to speak (TSV). */
    fun voiceScript(timeline: Timeline): String =
        timeline
            .captions()
            .filter { it.isVoice }
            .joinToString("") { "${it.id}\t${seconds(it.at)}\t${seconds(it.until)}\t${it.spoken}\n" }

    /** The sound cues: time (ms), file relative to the repository, gain (dB) and effect (TSV). */
    fun cues(timeline: Timeline): String =
        timeline
            .cues()
            .sortedBy { it.at }
            .joinToString("") { "${(it.at * MILLIS).roundToLong()}\t${it.file}\t${it.gainDb}\t${it.effect}\n" }

    private fun timestamp(seconds: Double): String {
        val ms = (seconds * MILLIS).roundToLong()
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d.%03d",
            ms / 3_600_000,
            ms / 60_000 % 60,
            ms / 1000 % 60,
            ms % 1000,
        )
    }

    private fun seconds(value: Double) = String.format(Locale.ROOT, "%.3f", value)

    private const val MILLIS = 1000.0
}
