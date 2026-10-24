package app.mauri.cucuto.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/**
 * The main journeys (start, Home, the character picker, Packs) as steps on the installed app, shared by the
 * Baseline Profile generator and any future Macrobenchmark. Each step does what it can and moves on: on a phone still
 * in onboarding, or without chats, the profile simply covers less.
 *
 * Labels come from the app's own resources in the phone's language, so the steps work in English and in Spanish.
 */
internal const val TARGET_PACKAGE = "app.mauri.cucuto"

/** Resource ID of a chat row on Home (`CHAT_ROW_TAG` in the app). */
private const val CHAT_ROW = "chat_row"

private const val TIMEOUT_MS = 5_000L

/** Starts the app from the launcher and waits for its first frame. */
internal fun MacrobenchmarkScope.start() {
    pressHome()
    startActivityAndWait()
}

/** Home: shows the chats and scrolls through them. */
internal fun MacrobenchmarkScope.home() {
    openTab("tab_home")
    scrollMainList()
}

/** The character picker: opens the first chat on Home (through its detail if it has a voice) and scrolls. */
internal fun MacrobenchmarkScope.picker() {
    openTab("tab_home")
    val chat = device.wait(Until.findObject(By.res(CHAT_ROW)), TIMEOUT_MS) ?: return
    chat.click()
    device.waitForIdle()
    // A chat with a voice opens its detail first; "Change" leads to the picker.
    device.findObject(By.text(appString("contact_change")))?.let {
        it.click()
        device.waitForIdle()
    }
    scrollMainList()
    device.pressBack()
    device.waitForIdle()
    openTab("tab_home")
}

/** Packs: the installed packs, scrolled through. */
internal fun MacrobenchmarkScope.packs() {
    openTab("tab_packs")
    scrollMainList()
}

/** Taps a tab of the navigation bar by its label (a string resource of the app). */
private fun MacrobenchmarkScope.openTab(label: String) {
    val tab = device.wait(Until.findObject(By.text(appString(label))), TIMEOUT_MS) ?: return
    tab.click()
    device.waitForIdle()
}

/** Flings the screen's main scrolling list down and back up. */
private fun MacrobenchmarkScope.scrollMainList() {
    val list = device.wait(Until.findObject(By.scrollable(true)), TIMEOUT_MS) ?: return
    // Keep the gesture away from the system's back and home gesture areas.
    list.setGestureMarginPercentage(GESTURE_MARGIN)
    list.fling(Direction.DOWN)
    device.waitForIdle()
    list.fling(Direction.UP)
    device.waitForIdle()
}

/** The app's string [name] in the phone's current language. */
private fun MacrobenchmarkScope.appString(name: String): String {
    val resources =
        InstrumentationRegistry
            .getInstrumentation()
            .context.packageManager
            .getResourcesForApplication(packageName)

    @Suppress("DiscouragedApi") // The generator is a separate APK: the app's R class is not on its classpath.
    val id = resources.getIdentifier(name, "string", packageName)
    return resources.getString(id)
}

private const val GESTURE_MARGIN = 0.2f
