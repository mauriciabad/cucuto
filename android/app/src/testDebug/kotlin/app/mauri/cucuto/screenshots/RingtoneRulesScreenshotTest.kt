package app.mauri.cucuto.screenshots

import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import app.mauri.cucuto.packspec.When
import app.mauri.cucuto.ui.ringtone.RingtoneSelection
import app.mauri.cucuto.ui.ringtone.RingtoneVariantItem
import app.mauri.cucuto.ui.ringtone.rules.RingtoneRulesActions
import app.mauri.cucuto.ui.ringtone.rules.RingtoneRulesContent
import app.mauri.cucuto.ui.ringtone.rules.RingtoneRulesState
import app.mauri.cucuto.ui.ringtone.rules.RulesDraft
import app.mauri.cucuto.ui.ringtone.rules.WhenChoice
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * The ringtone rules screen (#288, ADR 0074) for Iris, whose ringtone is Bleep the frog: the pack's pond song rings
 * by day and by night in plain words, and Iris has her own rules, the night song by day and the day song at night.
 * Sprites and ringtone files come from the demo packs.
 */
class RingtoneRulesScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun ringtoneRules() =
        screenshot("ringtone-rules") { RingtoneRulesContent(demoRulesState(), RingtoneRulesActions()) }

    companion object {
        /** Iris and the demo frog's day and night pond songs, with her own swapped rules. */
        fun demoRulesState(): RingtoneRulesState {
            val chat = DemoContent.chats().first { it.voice == DemoContent.critters.voice("frog") }
            val frog =
                DemoContent.critters
                    .voicePack()
                    .characters
                    .first { it.id == "frog" }

            fun entry(
                path: String,
                label: String,
                time: String,
            ) = RingtoneVariantItem(
                RingtoneVariant(path, label, listOf(time), When(time = JsonPrimitive(time))),
                File(DemoContent.critters.root, path),
            )
            val entries =
                listOf(
                    entry("frog/ringtones/pond-song-day.ogg", "Pond song (day)", "day"),
                    entry("frog/ringtones/pond-song-night.ogg", "Pond song (night)", "night"),
                )
            val rules =
                RulesDraft
                    .fromPack(entries.map { it.variant })
                    .choose(0, WhenChoice.NIGHT)
                    .choose(1, WhenChoice.DAY)
                    .rules
            return RingtoneRulesState(
                voice = frog.voice,
                chatKey = chat.key,
                loading = false,
                character = frog,
                packName = DemoContent.critters.name,
                entries = entries,
                chat = chat,
                saved = RingtoneSelection(frog.voice, followsVoice = true, mode = RingtoneMode.CUSTOM, rules = rules),
                ringsNow = true,
                now = LocalDateTime.parse("2026-10-24T12:00"),
            )
        }
    }
}
