package app.mauri.cucuto.screenshots

import app.mauri.cucuto.ui.onboarding.OnboardingContent
import app.mauri.cucuto.ui.onboarding.OnboardingStep
import app.mauri.cucuto.ui.status.ModuleCheck
import app.mauri.cucuto.ui.status.PacksCheck
import app.mauri.cucuto.ui.status.ScopeCheck
import app.mauri.cucuto.ui.status.SelfTestMessage
import app.mauri.cucuto.ui.status.StatusReport
import app.mauri.cucuto.ui.status.WhatsAppCheck
import org.junit.Test

/** First-run onboarding: the welcome step, and enabling the module with its live checks. */
class OnboardingScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun welcome() = onboarding("onboarding-welcome", OnboardingStep.Welcome, web = "onboarding")

    @Test
    fun enable() = onboarding("onboarding-enable", OnboardingStep.Enable, store = 6)

    private fun onboarding(
        name: String,
        step: OnboardingStep,
        web: String? = null,
        store: Int? = null,
    ) = screenshot(name, web = web, store = store, animated = true) {
        OnboardingContent(
            step = step,
            report =
                StatusReport(
                    module = ModuleCheck.Active(listOf("playSound", "playVibration"), updatePending = false),
                    scope = ScopeCheck.SystemOnly,
                    whatsApp = WhatsAppCheck.Found(listOf("com.whatsapp")),
                    packs = PacksCheck.Installed(2),
                ),
            selfTest = SelfTestMessage.None,
            onStep = {},
            onFinish = {},
            onAction = {},
            onPostSelfTest = {},
        )
    }
}
