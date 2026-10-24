package app.mauri.cucuto.screenshots

import app.mauri.cucuto.contact.ChatSettings
import app.mauri.cucuto.contact.ContactDetailContent
import app.mauri.cucuto.contact.ContactDetailState
import app.mauri.cucuto.contact.DecisionRow
import app.mauri.cucuto.contact.SimulatePath
import app.mauri.cucuto.contact.clipRows
import app.mauri.cucuto.core.data.db.DecisionOutcome
import org.junit.Test

/** Contact detail: Iris speaks as Bleep the frog, with its clips and the latest notifications it voiced. */
class ContactDetailScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun contactDetail() =
        screenshot("contact-detail", store = 3) {
            val now = System.currentTimeMillis()
            val chat = DemoContent.chats(now).first { it.voice == DemoContent.critters.voice("frog") }
            val frog =
                DemoContent.critters
                    .voicePack()
                    .characters
                    .first { it.id == "frog" }
            val settings = ChatSettings(disabledClips = emptyList(), hapticOverride = null)
            ContactDetailContent(
                state =
                    ContactDetailState(
                        chatKey = chat.key,
                        loading = false,
                        chat = chat,
                        character = frog,
                        packName = DemoContent.critters.name,
                        settings = settings,
                        clips = clipRows(frog, settings.disabledClips),
                        decisions =
                            listOf(
                                decision(3, now - 3 * HOUR, "ribbit"),
                                decision(2, now - 27 * HOUR, "hop"),
                                decision(1, now - 50 * HOUR, "ribbit"),
                            ),
                        path = SimulatePath.Module,
                    ),
                onBack = {},
                onChangeCharacter = {},
                onUnassign = {},
                onSimulate = {},
                onPlayClip = {},
                onToggleClip = { _, _ -> },
                onGroupPolicyChange = {},
                onConsumeFailure = {},
            )
        }

    private fun decision(
        id: Long,
        at: Long,
        clip: String,
    ) = DecisionRow(id, at, DecisionOutcome.SWAPPED, clip, simulated = false, perSender = false)

    private companion object {
        const val HOUR = 3_600_000L
    }
}
