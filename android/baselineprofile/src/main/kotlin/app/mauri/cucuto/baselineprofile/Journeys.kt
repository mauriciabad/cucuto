package app.mauri.cucuto.baselineprofile

import android.os.ParcelFileDescriptor
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * The main journeys (start, Home, contact detail, the ringtone picker and its rules, the character picker with
 * folding and search, add from contacts, Packs, the sample chat) as steps on the installed app, shared by the
 * Baseline Profile generator and any future Macrobenchmark. Each step does what it can and moves on: on a phone still
 * in onboarding, or without chats, the profile simply covers less.
 *
 * Labels come from the app's own resources in the phone's language, so the steps work in English and in Spanish.
 */
internal const val TARGET_PACKAGE = "app.mauri.cucuto"

/** Resource ID of a chat row on Home (`CHAT_ROW_TAG` in the app). */
private const val CHAT_ROW = "chat_row"

/** Resource ID of a pack's section header in the pickers (`PACK_HEADER_TAG` in the app). */
private const val PACK_HEADER = "pack_header"

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

/**
 * Contact detail of the first chat on Home, when it has a voice: scrolled, then its ringtone picker ("Phone calls")
 * and the ringtone rules, from the picker or else from the detail. Back on Home at the end.
 */
internal fun MacrobenchmarkScope.contactDetail() {
    if (!openFirstChatDetail()) return
    scrollMainList()
    if (tapInMainList(By.text(appString("contact_row_calls")))) {
        scrollMainList()
        if (tapInMainList(By.text(appString("ringtone_rules_open")))) {
            scrollMainList()
            back()
        }
        back()
    }
    // The rules again, from the detail's "Phone calls" row, when the ringtone has versions.
    if (tapInMainList(By.text(appString("ringtone_rules_open")))) back()
    backToHome()
}

/**
 * The character picker: the first chat on Home (through its detail when it has a voice), scrolled, a pack folded and
 * unfolded, and a search typed and cleared.
 */
internal fun MacrobenchmarkScope.picker() {
    openTab("tab_home")
    val chat = findInMainList(By.res(CHAT_ROW)) ?: return
    chat.click()
    device.waitForIdle()
    // A chat with a voice opens its detail first; "Change character" leads to the picker.
    device.findObject(By.text(appString("contact_change")))?.let {
        it.click()
        device.waitForIdle()
    }
    scrollMainList()
    device.wait(Until.findObject(By.res(PACK_HEADER)), TIMEOUT_MS)?.let { header ->
        click(header) // Folds the pack.
        device.findObject(By.res(PACK_HEADER))?.let(::click) // Unfolds it again.
    }
    search("picker_search_hint", "a")
    backToHome()
}

/** "Add a chat from contacts", from Home's "Assign a voice": the explainer or the list, scrolled and searched. */
internal fun MacrobenchmarkScope.addFromContacts() {
    openTab("tab_home")
    val assign = device.wait(Until.findObject(By.desc(appString("home_fab"))), TIMEOUT_MS) ?: return
    click(assign)
    if (!tapInMainList(By.text(appString("contacts_add")))) {
        back()
        return
    }
    scrollMainList()
    search("contacts_search_hint", "a")
    backToHome()
}

/** Packs: the installed packs, scrolled through. */
internal fun MacrobenchmarkScope.packs() {
    openTab("tab_packs")
    scrollMainList()
}

/**
 * The sample chat (ADR 0075), from Home's "Your characters are ready" card, which shows only while there are packs
 * and no chat. On a rooted session (`adb root` on the emulator) the chats are set aside in the app's database for
 * this step and put back afterwards, so the prepared emulator covers both Home states. Without root it only starts the
 * app: Home offers the sample chat only on a phone without chats.
 */
internal fun MacrobenchmarkScope.sampleChat() {
    if (shell("id -u").trim() != "0") {
        start()
        return
    }
    killProcess()
    shell(RESTORE_CHATS + HIDE_CHATS)
    try {
        start()
        openTab("tab_home")
        if (!tapInMainList(By.text(appString("home_journey_sample")))) return
        scrollMainList()
        device.findObject(By.text(appString("sample_chat_play_message")))?.let(::click)
        device.findObject(By.text(appString("sample_chat_play_call")))?.let(::click)
        device.wait(Until.findObject(By.text(appString("sample_chat_stop_call"))), TIMEOUT_MS)?.let(::click)
        back()
    } finally {
        killProcess()
        shell(RESTORE_CHATS)
    }
}

/** Opens the first chat on Home; true when it has a voice, so its contact detail is on screen. */
private fun MacrobenchmarkScope.openFirstChatDetail(): Boolean {
    openTab("tab_home")
    val chat = findInMainList(By.res(CHAT_ROW)) ?: return false
    click(chat)
    if (device.wait(Until.hasObject(By.text(appString("contact_change"))), TIMEOUT_MS)) return true
    back()
    return false
}

/** Taps a tab of the navigation bar by its label (a string resource of the app). */
private fun MacrobenchmarkScope.openTab(label: String) {
    val tab = device.wait(Until.findObject(By.text(appString(label))), TIMEOUT_MS) ?: return
    click(tab)
}

/** Taps the search field whose placeholder is the string [hint] and types [text]. */
private fun MacrobenchmarkScope.search(
    hint: String,
    text: String,
) {
    val field = findInMainList(By.text(appString(hint)), Direction.UP) ?: return
    click(field)
    device.findObject(By.focused(true))?.text = text
    device.waitForIdle()
    // The keyboard's search action: the pickers drop the focus, so the keyboard closes.
    device.pressEnter()
    device.waitForIdle()
}

/** Scrolls the main list until [selector] shows, then taps it. False when it never shows. */
private fun MacrobenchmarkScope.tapInMainList(selector: BySelector): Boolean {
    val target = findInMainList(selector) ?: return false
    click(target)
    return true
}

/** [selector] on screen, scrolling the main list towards [direction] a few times until it shows. */
private fun MacrobenchmarkScope.findInMainList(
    selector: BySelector,
    direction: Direction = Direction.DOWN,
): UiObject2? {
    device.wait(Until.findObject(selector), SHORT_TIMEOUT_MS)?.let { return it }
    repeat(FIND_SCROLLS) {
        val list = device.findObject(By.scrollable(true)) ?: return null
        try {
            list.setGestureMarginPercentage(GESTURE_MARGIN)
            list.scroll(direction, SCROLL_PERCENT)
        } catch (_: StaleObjectException) {
            // Replaced by a recomposition: look it up again.
        }
        device.waitForIdle()
        device.findObject(selector)?.let { return it }
    }
    return null
}

private fun MacrobenchmarkScope.click(target: UiObject2) {
    try {
        target.click()
    } catch (_: StaleObjectException) {
        return
    }
    device.waitForIdle()
}

/** Goes back until Home's search bar shows (a keyboard or a dialog can take a press), never past Home. */
private fun MacrobenchmarkScope.backToHome() {
    val home = By.text(appString("home_search_hint"))
    repeat(BACK_ATTEMPTS) {
        if (device.hasObject(home)) return
        back()
    }
}

private fun MacrobenchmarkScope.back() {
    device.pressBack()
    device.waitForIdle()
}

/** Flings the screen's main scrolling list down and back up. */
private fun MacrobenchmarkScope.scrollMainList() {
    fling(Direction.DOWN)
    fling(Direction.UP)
}

/**
 * Flings the main scrolling list once. The list is looked up for every gesture, and again when it went stale: a tab
 * switch that is still settling, or a recomposition, replaces the node.
 */
private fun MacrobenchmarkScope.fling(direction: Direction) {
    repeat(FLING_ATTEMPTS) {
        val list = device.wait(Until.findObject(By.scrollable(true)), TIMEOUT_MS) ?: return
        try {
            // Keep the gesture away from the system's back and home gesture areas.
            list.setGestureMarginPercentage(GESTURE_MARGIN)
            list.fling(direction)
            device.waitForIdle()
            return
        } catch (_: StaleObjectException) {
            device.waitForIdle()
        }
    }
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

/**
 * Runs [script] in a shell of the instrumentation's session (root after `adb root`) and returns its output. The script
 * goes through stdin, so it can quote freely (a plain shell command is split at every space).
 */
private fun shell(script: String): String {
    val (output, input) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
    ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(script.toByteArray()) }
    return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
}

private const val DATABASE = "/data/data/$TARGET_PACKAGE/databases/cucuto.db"

/** Sets every chat (seen senders and saved contacts) aside in side tables, so Home has no chat. */
private const val HIDE_CHATS =
    "sqlite3 $DATABASE \"" +
        "CREATE TABLE bp_hidden_senders AS SELECT * FROM seen_senders; DELETE FROM seen_senders; " +
        "CREATE TABLE bp_hidden_contacts AS SELECT * FROM contacts; DELETE FROM contacts;\"\n"

/** Puts the chats set aside by [HIDE_CHATS] back, if any. Safe to run again, e.g. after an interrupted run. */
private const val RESTORE_CHATS =
    "if [ -n \"$(sqlite3 $DATABASE \"SELECT name FROM sqlite_master WHERE name = 'bp_hidden_senders'\")\" ]; then " +
        "sqlite3 $DATABASE \"" +
        "INSERT OR IGNORE INTO seen_senders SELECT * FROM bp_hidden_senders; DROP TABLE bp_hidden_senders; " +
        "INSERT OR IGNORE INTO contacts SELECT * FROM bp_hidden_contacts; DROP TABLE bp_hidden_contacts;\"; fi\n"

private const val GESTURE_MARGIN = 0.2f

private const val FLING_ATTEMPTS = 3

private const val FIND_SCROLLS = 4

private const val SCROLL_PERCENT = 0.8f

private const val SHORT_TIMEOUT_MS = 1_000L

private const val BACK_ATTEMPTS = 4
