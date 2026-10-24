package app.mauri.cucuto.screenshots

import app.mauri.cucuto.contact.ChatSettings
import app.mauri.cucuto.contact.ContactDetailContent
import app.mauri.cucuto.contact.ContactDetailState
import app.mauri.cucuto.contact.DecisionRow
import app.mauri.cucuto.contact.SimulatePath
import app.mauri.cucuto.contact.clipRows
import app.mauri.cucuto.core.data.db.DecisionOutcome
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.ui.ringtone.PhoneCallsRowContent
import app.mauri.cucuto.ui.ringtone.PhoneRingtoneUiState
import app.mauri.cucuto.ui.ringtone.RingSound
import app.mauri.cucuto.ui.ringtone.VariantChip
import org.junit.Test
import java.io.File

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
                // Messages and Phone calls are independent (ADR 0063): the frog's song rings, day and night.
                phoneCalls = {
                    PhoneCallsRowContent(
                        state =
                            PhoneRingtoneUiState(
                                loading = false,
                                enabled = true,
                                contactId = chat.contactId,
                                characterName = frog.name,
                                hasCharacter = true,
                                variants = listOf(VariantChip("day.ogg", "Day"), VariantChip("night.ogg", "Night")),
                                using = true,
                                // Linked: the contact and the WhatsApp hint sit under the row (#348).
                                linked = true,
                                linkedName = chat.displayName,
                                exportTitle = "${frog.name} · Day",
                                mode = RingtoneMode.TIME,
                                modes =
                                    listOf(
                                        RingtoneMode.PACK,
                                        RingtoneMode.RANDOM,
                                        RingtoneMode.TIME,
                                        RingtoneMode.FIXED,
                                    ),
                                ringingCharacter = frog,
                                previewSound = RingSound(File("/demo/frog/ringtones/day.ogg")),
                            ),
                        onChoose = {},
                        onTogglePreview = {},
                    )
                },
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
