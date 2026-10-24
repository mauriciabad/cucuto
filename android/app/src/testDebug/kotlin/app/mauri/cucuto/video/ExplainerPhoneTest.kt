package app.mauri.cucuto.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import app.mauri.cucuto.R
import app.mauri.cucuto.contact.ChatSettings
import app.mauri.cucuto.contact.ContactDetailContent
import app.mauri.cucuto.contact.ContactDetailState
import app.mauri.cucuto.contact.DecisionRow
import app.mauri.cucuto.contact.SimulatePath
import app.mauri.cucuto.contact.SimulationStatus
import app.mauri.cucuto.contact.clipRows
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.DecisionOutcome
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.home.HomeContent
import app.mauri.cucuto.home.buildHomeState
import app.mauri.cucuto.home.homeJourney
import app.mauri.cucuto.navigation.AppNavigator
import app.mauri.cucuto.navigation.CucutoDestination
import app.mauri.cucuto.navigation.CucutoShellLayout
import app.mauri.cucuto.navigation.TabRootMetadata
import app.mauri.cucuto.navigation.TopLevelTab
import app.mauri.cucuto.packs.ImportUi
import app.mauri.cucuto.packs.PacksMessage
import app.mauri.cucuto.packs.ui.ImportReviewScreen
import app.mauri.cucuto.picker.PickerContent
import app.mauri.cucuto.picker.PickerState
import app.mauri.cucuto.screenshots.DemoContent
import app.mauri.cucuto.screenshots.ScreenshotTest
import app.mauri.cucuto.ui.discover.DemoPacksUi
import app.mauri.cucuto.ui.discover.DiscoverActions
import app.mauri.cucuto.ui.discover.DiscoverContent
import app.mauri.cucuto.ui.discover.DiscoverMessage
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackBeat
import app.mauri.cucuto.ui.feedback.LocalFeedback
import app.mauri.cucuto.ui.onboarding.OnboardingContent
import app.mauri.cucuto.ui.onboarding.OnboardingStep
import app.mauri.cucuto.ui.status.ModuleCheck
import app.mauri.cucuto.ui.status.PacksCheck
import app.mauri.cucuto.ui.status.ScopeCheck
import app.mauri.cucuto.ui.status.SelfTestMessage
import app.mauri.cucuto.ui.status.StatusReport
import app.mauri.cucuto.ui.status.WhatsAppCheck
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The explainer video's phone shots (docs/explainer-video.md, shot list), recorded from the app's real screens with the
 * made-up cast: the lock screen stand-ins (shots 1 and 9), onboarding (shot 3) and one continuous take of the app in
 * its shell from the demo packs to Simulate message (shots 4 to 8). The screens are the stateless contents the
 * screenshot tests use; the taps are real touch input, and each callback changes the state as the view model would.
 */
@Config(qualifiers = ScreenshotTest.DEVICE)
internal class ExplainerPhoneTest(
    language: String,
) : ExplainerTest(language) {
    /** Shot 1, 0–5 s: three notifications, the same plain ping three times. Stand-in for device footage. */
    @Test
    fun shot01SamePing() {
        val recorder = SceneRecorder(compose, sceneDir("01-same-ping"), "phone", 0, 5_000)
        val notifications =
            listOf(
                StandInNotification("Ada", strings.ada),
                StandInNotification("Bea", strings.bea),
                StandInNotification("Leo", strings.leo),
            )
        val visible = mutableStateListOf(false, false, false)
        scene(recorder, script = {
            listOf(0.6, 1.6, 2.6).forEachIndexed { index, time ->
                at(time - FRAME) { visible[index] = true }
                cue(time, TAP, UI_DB, effect = "octave-down")
            }
        }) { LockScreenStandIn(notifications, visible, strings) }
    }

    /**
     * Shot 3, 9–15 s: onboarding. Welcome, Next to "Before you start" (Android 17, root, LSPosed), a quick wipe past
     * the module and reboot pages to the self-test, Post the self-test, and the door pops open in green.
     */
    @Test
    fun shot03SetUpOnce() {
        val recorder = SceneRecorder(compose, sceneDir("03-set-up-once"), "phone", 9_000, 15_000)
        var step by mutableStateOf(OnboardingStep.Welcome)
        var module by mutableStateOf<ModuleCheck>(ModuleCheck.AwaitingSelfTest)
        var selfTest by mutableStateOf<SelfTestMessage>(SelfTestMessage.None)
        // The welcome door has greeted the viewer before the cut.
        scene(recorder, preRollMs = 2_500, script = {
            tap(10.4) { compose.onNodeWithText(str(R.string.onboarding_next)) }
            at(11.9) { step = OnboardingStep.SelfTest }
            tap(12.7) { compose.onAllNodesWithText(str(R.string.status_action_self_test)).onFirst() }
            at(
                13.8 - FRAME,
            ) { module = ModuleCheck.Active(listOf("playSound", "playVibration"), updatePending = false) }
            cue(13.8, SUCCESS, UI_DB)
        }) {
            OnboardingContent(
                step = step,
                report =
                    StatusReport(
                        module = module,
                        scope = ScopeCheck.SystemOnly,
                        whatsApp = WhatsAppCheck.Found(listOf("com.whatsapp")),
                        packs = PacksCheck.None,
                    ),
                selfTest = selfTest,
                onStep = { step = it },
                onFinish = {},
                onAction = {},
                onPostSelfTest = { selfTest = SelfTestMessage.Posted },
            )
        }
    }

    /**
     * Shots 4–8, 15–46 s, one take in the app shell: get the demo packs (Discover finds the bundle, the review installs
     * it), the recent senders and "Give Ada a voice", the picker (tap to hear, tap again to assign), back on Home the
     * cuckoo door, and Ada's contact detail with Simulate message.
     */
    @Test
    fun shots04to08App() {
        val recorder = SceneRecorder(compose, sceneDir("04-08-app"), "phone", 15_000, 46_000)
        val app = AppTake(recorder)
        scene(recorder, script = { app.script(this) }) { app.Content() }
    }

    /** Shot 9, 46–53 s: messages from Ada (the frog) and Bea (the kettle) on the locked phone. Stand-in for device footage. */
    @Test
    fun shot09TheRealThing() {
        val recorder = SceneRecorder(compose, sceneDir("09-the-real-thing"), "phone", 46_000, 53_000)
        val notifications = listOf(StandInNotification("Ada", strings.adaReal), StandInNotification("Bea", strings.bea))
        val visible = mutableStateListOf(false, false)
        scene(recorder, script = {
            at(46.8 - FRAME) { visible[0] = true }
            cue(46.8, clip("chiptune-critters", "frog", "ribbit"), CLIP_DB)
            ripple(46.8)
            at(49.4 - FRAME) { visible[1] = true }
            cue(49.4, clip("kitchen-orchestra", "kettle", "whistle"), CLIP_DB)
            ripple(49.4)
        }) { LockScreenStandIn(notifications, visible, strings) }
    }

    /** The app take of shots 4–8: its state, what each tap does, and when. */
    private inner class AppTake(
        private val recorder: SceneRecorder,
    ) {
        private val navigator =
            AppNavigator(
                stacks = TopLevelTab.entries.associateWith { mutableStateListOf<NavKey>(it.root) },
                selected = mutableStateOf(TopLevelTab.Home),
            )
        private val now = System.currentTimeMillis()
        private val voicePacks = ExplainerCast.packs.map { it.voicePack() }
        private val frog: VoiceCharacter = voicePacks.first().characters.first { it.id == "frog" }

        var packsInstalled by mutableStateOf(false)
        var adaVoice by mutableStateOf<ChatVoice?>(null)
        var demo by mutableStateOf<DemoPacksUi?>(null)
        var discoverMessage by mutableStateOf<DiscoverMessage?>(null)
        var import by mutableStateOf<ImportUi>(ImportUi.Idle)
        var picker by mutableStateOf(PickerState())
        var assignBeat by mutableStateOf<FeedbackBeat?>(null)
        var contact by mutableStateOf(ContactDetailState(ExplainerCast.ada))

        fun script(scene: SceneRecorder) =
            with(scene) {
                // Shot 4: Home offers the demo packs; Discover finds the bundle; the review installs it.
                tap(15.9) { compose.onNodeWithText(str(R.string.home_get_demo_packs)) }
                at(16.7) {
                    demo = DemoPacksUi.Importing
                    import = ExplainerCast.downloading(0)
                    navigator.navigate(CucutoDestination.ImportReview(CucutoDestination.ImportFrom.Discover))
                }
                for (i in 1..8) {
                    at(16.7 + i * 0.1) {
                        import =
                            ExplainerCast.downloading(ExplainerCast.BUNDLE_BYTES * i / 8)
                    }
                }
                at(17.6) { import = ExplainerCast.review() }
                tap(19.0) { compose.onNodeWithText(installText()) }
                at(19.8) {
                    import = ImportUi.Idle
                    demo = null
                    packsInstalled = true
                    navigator.goBack()
                }
                at(20.0 - FRAME) {
                    discoverMessage =
                        DiscoverMessage.Installed(
                            PacksMessage.Installed(ExplainerCast.packs.map { it.name }, characters = 8, skipped = 0),
                        )
                }
                cue(20.0, SUCCESS, UI_DB)
                tap(20.6) { compose.onNode(hasText(str(R.string.tab_home)) and isSelectable()) }

                // Shot 5: the recent senders and the invitation to give Ada a voice.
                tap(24.9) { compose.onNodeWithText(str(R.string.home_journey_first_button)) }
                cue(25.0, TAP, UI_DB)

                // Shot 6: tap to hear, tap again to assign. The clicks land on the brief's times (the finger lifts).
                tapCharacter(27.8, "cat", "Mew the cat", clip("chiptune-critters", "cat", "mew"))
                drag(28.15, durationMs = 450, from = Offset(0.5f, 0.75f), to = Offset(0.5f, 0.45f)) {
                    compose.onNode(hasScrollAction())
                }
                tapCharacter(28.9, "kettle", "Tootle the kettle", clip("kitchen-orchestra", "kettle", "whistle"))
                tapCharacter(30.0, "frog", "Bleep the frog", clip("chiptune-critters", "frog", "ribbit"))
                // Back down, so Ada's header (avatar and "now speaks for") is on screen for the assignment.
                drag(30.5, durationMs = 450, from = Offset(0.5f, 0.45f), to = Offset(0.5f, 0.8f)) {
                    compose.onNode(hasScrollAction())
                }
                tapCharacter(32.6, "frog", "Bleep the frog", null)
                cue(32.6, ASSIGN, UI_DB)
                cue(32.73, clip("chiptune-critters", "frog", "ribbit"), CLIP_DB)

                // Shot 7: back on Home, the first-voice card's cuckoo door pops open on the frog. The real picker
                // closes 0.9 s after assigning; the edit holds it until the door's time on the brief's timeline.
                at(36.4) { navigator.goBack() }
                cue(36.45, ASSIGN, UI_DB)
                cue(36.58, clip("chiptune-critters", "frog", "croak"), CLIP_DB)

                // Shot 8: Ada's contact detail, Simulate message through the module.
                tap(40.1) { compose.onAllNodesWithText("Ada").filter(hasClickAction()).onFirst() }
                tap(41.5) { compose.onNodeWithContentDescription(str(R.string.contact_simulate), substring = true) }
                at(41.9) { contact = contact.copy(simulation = SimulationStatus.Sent(now)) }
                cue(43.3, clip("chiptune-critters", "frog", "ribbit"), CLIP_DB)
                ripple(43.3)
                at(43.8) {
                    val row =
                        DecisionRow(1, now, DecisionOutcome.SWAPPED, "ribbit", simulated = true, perSender = false)
                    contact = contact.copy(report = row, decisions = listOf(row))
                }
            }

        /** A tap on [name] in the picker whose click (finger up) lands at [seconds], playing [clip] for a preview. */
        private fun SceneRecorder.tapCharacter(
            seconds: Double,
            id: String,
            name: String,
            clip: String?,
        ) {
            tap(seconds - TAP_HOLD) { compose.onNodeWithContentDescription(name) }
            if (clip != null) {
                cue(seconds, clip, CLIP_DB)
                at(seconds + PREVIEW_SECONDS) {
                    if (picker.speaking?.characterId == id) picker = picker.copy(speaking = null)
                }
            }
        }

        private fun installText() = plural(R.plurals.import_install_packs, ExplainerCast.packs.size)

        /** What the picker's view model does with a tap: preview first, assign on the second tap of the same one. */
        private fun onPickerTap(
            character: VoiceCharacter,
            fire: (Feedback) -> FeedbackBeat,
        ) {
            val voice = ChatVoice(character.packId, character.id)
            if (picker.selected == voice && picker.assigned == null) {
                assignBeat = fire(Feedback.Assign(character.id))
                picker = picker.copy(assigned = voice, speaking = null)
                adaVoice = voice
            } else {
                picker = picker.copy(selected = voice, speaking = voice)
            }
        }

        @Composable
        fun Content(modifier: Modifier = Modifier) {
            val feedback = LocalFeedback.current
            val entries =
                remember {
                    entryProvider<NavKey> {
                        entry<CucutoDestination.Home>(metadata = TabRootMetadata) { Home() }
                        entry<CucutoDestination.Packs>(metadata = TabRootMetadata) { Box(Modifier.fillMaxSize()) }
                        entry<CucutoDestination.Discover>(metadata = TabRootMetadata) { Discover() }
                        entry<CucutoDestination.Settings>(metadata = TabRootMetadata) { Box(Modifier.fillMaxSize()) }
                        entry<CucutoDestination.ImportReview> {
                            ImportReviewScreen(
                                import = import,
                                onSelect = { _, _ -> },
                                onInstall = { import = ExplainerCast.review(installing = true) },
                                onPickAnother = null,
                                onBack = {},
                            )
                        }
                        entry<CucutoDestination.Picker> {
                            PickerContent(
                                state = picker,
                                assignBeat = assignBeat,
                                onTap = { onPickerTap(it, feedback::fire) },
                                onUnassign = {},
                                onBack = {},
                                onOpenPacks = {},
                            )
                        }
                        entry<CucutoDestination.ContactDetail> {
                            ContactDetailContent(
                                state = contact,
                                onBack = {},
                                onChangeCharacter = {},
                                onUnassign = {},
                                onSimulate = { contact = contact.copy(simulation = SimulationStatus.Working) },
                                onPlayClip = {},
                                onToggleClip = { _, _ -> },
                                onGroupPolicyChange = {},
                                onConsumeFailure = {},
                            )
                        }
                    }
                }
            CucutoShellLayout(navigator = navigator, entryProvider = entries, modifier = modifier)
        }

        @Composable
        private fun Home() {
            val chats = ExplainerCast.chats(now, adaVoice)
            val state =
                buildHomeState(
                    chats = chats,
                    packs = if (packsInstalled) voicePacks else emptyList(),
                    query = "",
                    packFilter = null,
                    now = now,
                )
            HomeContent(
                state = state,
                onQueryChange = {},
                onTogglePack = {},
                onDismissRecent = {},
                onOpenChat = { key ->
                    picker =
                        PickerState(
                            loading = false,
                            chatKey = key,
                            chat = chats.first { it.key == key },
                            packs = voicePacks,
                        )
                    navigator.navigate(CucutoDestination.Picker(key))
                },
                onOpenPacks = {},
                onOpenDetail = { key ->
                    contact =
                        ContactDetailState(
                            chatKey = key,
                            loading = false,
                            chat = chats.first { it.key == key },
                            character = frog,
                            packName = DemoContent.critters.name,
                            settings = ChatSettings(disabledClips = emptyList(), hapticOverride = null),
                            clips = clipRows(frog, emptyList()),
                            path = SimulatePath.Module,
                        )
                    navigator.navigate(CucutoDestination.ContactDetail(key))
                },
                journey = homeJourney(state, firstVoiceCelebrated = false, celebrating = null),
                onGetDemoPacks = {
                    demo = DemoPacksUi.Finding
                    navigator.navigate(CucutoDestination.Discover)
                },
            )
        }

        @Composable
        private fun Discover() {
            DiscoverContent(
                state = ExplainerCast.discover(demo, packsInstalled).copy(message = discoverMessage),
                previews = ExplainerCast.previews,
                actions = DiscoverActions(onConsumeMessage = { discoverMessage = null }),
                onOpenRepositories = {},
            )
        }
    }

    private companion object {
        /** One video frame, in seconds: state set this early shows on the frame at the cue's time. */
        const val FRAME = 1.0 / FPS
        const val TAP_HOLD = 0.09
        const val PREVIEW_SECONDS = 0.7
    }
}
