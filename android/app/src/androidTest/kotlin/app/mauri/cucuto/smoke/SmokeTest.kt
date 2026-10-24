package app.mauri.cucuto.smoke

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import app.mauri.cucuto.MainActivity
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.repository.SeenSenderRepository
import app.mauri.cucuto.core.data.routing.PublishedTableStore
import app.mauri.cucuto.core.data.routing.RoutingPublisher
import app.mauri.cucuto.core.data.settings.OnboardingSettings
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.core.pack.PackSelection
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.packs.ImportStep
import app.mauri.cucuto.packs.PackLibrary
import dagger.hilt.android.testing.HiltAndroidRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.io.File
import javax.inject.Inject

/**
 * Base of the end-to-end smoke tests (docs/maintaining.md): the real app (Room database, importer, routing publisher,
 * backups, navigation) on Hilt's test application, driven through its UI with the CC0 demo packs, which the build
 * makes with the packer (`demoPacks` in app/build.gradle.kts).
 *
 * Every test runs in its own process on a cleared app (Orchestrator, `clearPackageData`), so it starts like a fresh
 * install. Nothing sleeps: Compose idling covers the UI, and [waitFor] waits for a node that appears after background
 * work (database writes, imports) instead of a fixed delay. Nodes are found by string resources (renamed strings fail
 * to compile, reworded ones still match) and content descriptions.
 */
abstract class SmokeTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose: ComposeTestRule = createEmptyComposeRule()

    @Inject lateinit var publisher: RoutingPublisher

    @Inject lateinit var publishedTable: PublishedTableStore

    @Inject lateinit var library: PackLibrary

    @Inject lateinit var onboarding: OnboardingSettings

    @Inject lateinit var senders: SeenSenderRepository

    @Inject lateinit var clock: WallClock

    private var scenario: ActivityScenario<MainActivity>? = null

    protected val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUpSmoke() {
        hilt.inject()
        // System file pickers are answered by the tests (see [answerFilePicker]); nothing leaves the app.
        Intents.init()
    }

    @After
    fun tearDownSmoke() {
        Intents.release()
        scenario?.close()
    }

    /**
     * Opens the app as a user would, after what `CucutoApplication.onCreate` starts (the routing publisher). With
     * [onboarded], the first-run onboarding counts as done, so the app opens on Home.
     */
    protected fun startApp(
        onboarded: Boolean = true,
        intent: Intent? = null,
    ) {
        if (onboarded) runBlocking { onboarding.setCompleted() }
        publisher.start()
        scenario =
            if (intent == null) {
                ActivityScenario.launch(MainActivity::class.java)
            } else {
                ActivityScenario.launch(intent.setClass(context, MainActivity::class.java))
            }
    }

    // ---- Data the module would report, and packs installed the way the Packs screen does ----

    /** Records a 1:1 WhatsApp chat as the module does when its first message alerts. */
    protected fun messageFrom(
        name: String,
        key: String = ANA_KEY,
    ): ChatKey {
        runBlocking { senders.record(ChatKey.WHATSAPP, key, name, isGroup = false, seenAt = clock.now()) }
        return ChatKey(ChatKey.WHATSAPP, key)
    }

    /** Installs a demo pack through the real importer, as the import review's Install button does. */
    protected fun installDemoPack(pack: String = CHIPTUNE) {
        runBlocking {
            val ready = library.prepare(ImportSource.of(demoPackFile(pack))).first { it.isFinal() }
            check(ready is ImportStep.Ready) { "Demo pack $pack did not validate: $ready" }
            val selection =
                PackSelection(
                    ready.review.packs
                        .map { it.id }
                        .toSet(),
                )
            val done = library.install(ready.review, selection).first { it.isFinal() }
            check(done is ImportStep.Installed) { "Demo pack $pack did not install: $done" }
        }
    }

    /** A demo pack archive from the test APK, copied where the app may read it (its external files folder). */
    protected fun demoPackFile(pack: String): File {
        val folder = checkNotNull(context.getExternalFilesDir("smoke")) { "No external files folder" }
        return File(folder, "$pack.soundpack.zip").also { file ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("$pack.soundpack.zip").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
    }

    /** Answers the next system file picker with [action] (open or create a document) with [file]. */
    protected fun answerFilePicker(
        action: String,
        file: File,
    ) {
        intending(hasAction(action))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
    }

    // ---- The routing table, as the publisher wrote it for the module ----

    /** Waits until the publisher's copy of the table (what the module reads) satisfies [check], and returns it. */
    protected fun waitForPublishedTable(check: (RoutingTable) -> Boolean): RoutingTable {
        var table: RoutingTable? = null
        compose.waitUntil(TIMEOUT_MS) {
            table = runBlocking { RoutingCodec.decodeOrNull(publishedTable.read()) }
            table?.let(check) == true
        }
        return checkNotNull(table)
    }

    // ---- UI helpers ----

    protected fun str(
        @StringRes id: Int,
        vararg args: Any,
    ): String = context.getString(id, *args)

    /** Waits until a node matching [matcher] is in the tree (after background work), and returns the first one. */
    protected fun waitFor(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(
            TIMEOUT_MS,
        ) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher, useUnmergedTree = true)[0]
    }

    /**
     * Waits until the screen's scrolling list holds a node matching [matcher], scrolling to it: a lazy list (Home's
     * chats below the first-run and setup cards) only composes what is on screen.
     */
    protected fun scrollListTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(matcher) }.isSuccess
        }
        return compose.onAllNodes(matcher, useUnmergedTree = true)[0]
    }

    /** Home lists [ANA] with [character] of [pack] as voice. */
    protected fun waitForAnaOnHome(
        character: String = FROG,
        pack: String = CHIPTUNE_NAME,
    ) = scrollListTo(hasText(str(R.string.home_chat_voice, character, pack), substring = true))

    /** Waits until no node matches [matcher]. */
    protected fun waitForGone(matcher: SemanticsMatcher) {
        compose.waitUntil(
            TIMEOUT_MS,
        ) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    protected fun waitForText(
        @StringRes id: Int,
        vararg args: Any,
        substring: Boolean = false,
    ): SemanticsNodeInteraction = waitFor(hasText(str(id, *args), substring = substring))

    /**
     * Clicks the clickable node with [text] (or that content description), scrolling to it first. Clicks go through
     * semantics, so a button half behind a bottom bar on a small screen still works.
     */
    protected fun tap(text: String) {
        val matcher = (hasText(text) or hasContentDescription(text)) and hasClickAction()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodes(matcher)[0]
        runCatching { node.performScrollTo() }
        node.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    protected fun tap(
        @StringRes id: Int,
        vararg args: Any,
    ) = tap(str(id, *args))

    // ---- Journeys shared by several tests ----

    /**
     * From Home's first-run card, gives [ANA] the message voice [character] in the picker (a tap previews, a second
     * tap assigns), which then opens the ringtone picker as the optional step 2 (#318, ADR 0073).
     */
    protected fun giveAnaAVoice(character: String = FROG) {
        tap(R.string.home_journey_first_button)
        waitForText(R.string.picker_title)
        tap(character)
        tap(character)
        waitForText(R.string.ringtone_picker_step)
    }

    /** Waits until the routing table the app publishes for the module routes [ANA] to [characterId] (or any voice). */
    protected fun waitForAnaRoutedTo(characterId: String?): RoutingTable =
        waitForPublishedTable { table ->
            table.routes.any { route ->
                route.key == ANA_KEY &&
                    route.voice != Route.NO_VOICE &&
                    (characterId == null || table.voices[route.voice].characterId == characterId)
            }
        }

    private fun ImportStep.isFinal(): Boolean =
        this is ImportStep.Ready || this is ImportStep.Installed || this is ImportStep.Invalid ||
            this is ImportStep.Failed || this is ImportStep.DownloadFailed

    companion object {
        /** Generous for a cold emulator in CI; a passing run waits far less. */
        const val TIMEOUT_MS = 20_000L

        const val CHIPTUNE = "chiptune-critters"
        const val KITCHEN = "kitchen-orchestra"
        const val CHIPTUNE_ID = "app.mauri.demo.chiptune-critters"
        const val CHIPTUNE_NAME = "Chiptune critters"

        /** A made-up WhatsApp identity (`@lid`), never a real one. */
        const val ANA_KEY = "100000000000001@lid"
        const val ANA = "Ana Smoke"

        const val FROG = "Bleep the frog"
        const val BIRD = "Tweet the bird"
    }
}
