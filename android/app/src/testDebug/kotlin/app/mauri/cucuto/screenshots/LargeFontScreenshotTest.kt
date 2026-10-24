package app.mauri.cucuto.screenshots

import app.mauri.cucuto.a11y.A11yScreens
import org.junit.Test

/**
 * Every screen of the accessibility pass (#29) at Android's largest font size (200%), in English and in Spanish,
 * with the made-up content of [A11yScreens]. `LargeFontScreensTest` checks the same screens for cut-off text; these
 * goldens show how they reflow.
 */
class LargeFontScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test fun statusCard() = largeFont("status-card")

    @Test fun onboardingRequirements() = largeFont("onboarding-requirements")

    @Test fun onboardingEnable() = largeFont("onboarding-enable")

    @Test fun home() = largeFont("home")

    @Test fun homeEmpty() = largeFont("home-empty")

    @Test fun picker() = largeFont("picker")

    @Test fun packs() = largeFont("packs")

    @Test fun discover() = largeFont("discover")

    @Test fun contactDetail() = largeFont("contact-detail")

    @Test fun settings() = largeFont("settings")

    @Test fun hapticEditor() = largeFont("haptic-editor")

    @Test fun phoneRingtone() = largeFont("phone-ringtone")

    @Test fun creatorPack() = largeFont("creator-pack")

    @Test fun creatorCharacter() = largeFont("creator-character")

    @Test fun creatorRecorder() = largeFont("creator-recorder")

    @Test fun creatorClipEditor() = largeFont("creator-clip-editor")

    @Test fun restore() = largeFont("restore")

    @Test fun backupExport() = largeFont("backup-export")

    @Test fun licenses() = largeFont("licenses")

    @Test fun importReview() = largeFont("import-review")

    @Test fun packDetail() = largeFont("pack-detail")

    @Test fun repositoryForm() = largeFont("repository-form")

    @Test fun repositories() = largeFont("repositories")

    @Test fun creatorFinish() = largeFont("creator-finish")

    @Test fun assignVoice() = largeFont("assign-voice")

    @Test fun creatorCropper() = largeFont("creator-cropper")

    @Test fun diagnostics() = largeFont("diagnostics")

    @Test fun dayNight() = largeFont("day-night")

    @Test fun restorePassphrase() = largeFont("restore-passphrase")

    @Test fun restorePacks() = largeFont("restore-packs")

    @Test fun restoreConflicts() = largeFont("restore-conflicts")

    @Test fun ringtonePicker() = largeFont("ringtone-picker")

    @Test fun sampleChat() = largeFont("sample-chat")

    private fun largeFont(screen: String) =
        // The onboarding's wavy progress bar never stops moving.
        screenshot(screen, fontScale = LARGEST_FONT_SCALE, animated = screen.startsWith("onboarding-")) {
            A11yScreens.content(screen)()
        }

    private companion object {
        const val LARGEST_FONT_SCALE = 2f
    }
}
