package app.mauri.cucuto.smoke

import android.content.Intent
import androidx.compose.ui.test.hasText
import app.mauri.cucuto.R
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

/** First run: onboarding, then the demo packs from a file, through the import review. */
@HiltAndroidTest
class FirstRunSmokeTest : SmokeTest() {
    @Test
    fun onboardingEndsOnHomeAndTheDemoPackImportsFromAFile() {
        startApp(onboarded = false)

        // Five steps; on an emulator the module is never active, so the last button is "Finish later".
        waitForText(R.string.onboarding_step, 1, 5)
        repeat(4) { tap(R.string.onboarding_next) }
        waitForText(R.string.onboarding_step, 5, 5)
        tap(R.string.onboarding_later)

        // Home's first-run journey offers the demo packs; the file picker answers with the built demo pack.
        waitForText(R.string.home_journey_import_title)
        answerFilePicker(Intent.ACTION_OPEN_DOCUMENT, demoPackFile(CHIPTUNE))
        tap(R.string.home_journey_import_button)

        // The Packs tab reviews it, then installs it.
        waitForText(R.string.import_install)
        tap(R.string.import_install)
        waitFor(hasText("Chiptune critters"))
        waitForGone(hasText(str(R.string.import_install)))

        // Home moves on to the next step: waiting for the first message.
        tap(R.string.tab_home)
        waitForText(R.string.home_journey_wait_title)
    }

    @Test
    fun openWithImportsAPackSharedByAnotherApp() {
        // A browser download opened from its notification: a content: URI (file: URIs are refused, ADR 0079).
        val uri = demoPackDownload(KITCHEN)
        startApp(
            intent =
                Intent(
                    Intent.ACTION_VIEW,
                ).setDataAndType(uri, "application/zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )

        waitForText(R.string.import_install)
        tap(R.string.import_install)
        waitFor(hasText("Kitchen orchestra"))
        waitForGone(hasText(str(R.string.import_install)))
    }
}
