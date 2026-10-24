# 0027. App shell: four tabs, per-tab back stacks and the first-run journey

- Status: accepted (the owner may override the defaults below, see the `question` issue linked from #153)
- Date: 2026-10-24
- Issue: #153

## Context

The owner: "right now the app feels like it is fragmented." Status was the start screen; Home, Packs and the rest
were reached through a debug-style "Preview screens" list, and Settings, Discover, the contact detail and the haptic
editor were placeholders. Nothing tied onboarding (#24, ADR 0015), Home and the picker (#25, ADR 0017) and Packs
(#27, ADR 0016) into one flow. Several agents build screens at the same time (#26, #28, #36, #32), so the structure
must leave each one a small, separate place to plug in.

Guidelines read: Material 3 [navigation bar](https://m3.material.io/components/navigation-bar/guidelines) and
adaptive navigation (bar on compact windows, rail on larger ones; reselecting a tab returns to its top; back from a
tab other than the start one goes to the start one), Compose
[`NavigationSuiteScaffold`](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)
(Material 3 adaptive navigation suite 1.5.0-beta02, released with Material 3) and the Navigation 3 "multiple back
stacks" pattern (one `NavBackStack` per top-level route, each decorated with `rememberDecoratedNavEntries`, shown
in one `NavDisplay`).

## Decision

**Stages.** `CucutoApp` shows a blank surface while the settings load, the onboarding flow full screen on first
run (`onboarding_completed` false), and then the shell. Finishing or skipping onboarding lands on Home.
`appStage(onboarded)` is a pure function.

**Shell.** `NavigationSuiteScaffold` with four tabs: **Home**, **Packs**, **Discover**, **Settings**
(`TopLevelTab`). `AppNavigator` holds one back stack per tab (saveable `NavBackStack`s) and the selected tab:

- navigating to a tab root switches tabs and keeps each tab's stack; tapping the selected tab pops it to its root;
- any other destination is pushed on the current tab, so back returns where the user came from (Diagnostics can sit
  in Home's stack, from the setup card, and in Settings' at once; content keys are prefixed with the tab);
- back pops the current stack; from another tab's root it goes to Home; from Home's root the system closes the app.
  `NavDisplay` shows Home's stack under the current one, so predictive back from a tab root previews Home.

Tab roots fade through (fade plus a 0.94 scale-in); pushed screens keep the shared-axis slide. All transitions use
the expressive motion scheme's springs, and predictive back scrubs the same specs. The bar hides for full-screen
flows (the setup guide reopened from Setup). Tab icons missing from `material-icons-core` (Packs: "library_music",
Discover: "explore") are drawn from the Material Icons path data instead of adding the extended icon set.

**Screens plug in with one entry.** `cucutoEntries` maps each destination to a self-contained composable with
`onNavigate: (CucutoDestination) -> Unit` and, for pushed screens, `onBack`. Settings (#28) is the tab root as
`SettingsScreen(onNavigate)`; its status row opens Setup and its debug-only row opens Developer. The contact detail
(#26) is pushed on Home for chats that have a voice. Discover (#36) is the tab root as
`DiscoverScreen()` (no back arrow); an "Add to app" link opens the Discover tab, as an incoming pack opens Packs.
The haptic editor (#32) is pushed on the current tab. Screens that work on their parent's view model (the import review over Packs, "Check and finish" over the
creator) declare it with `childOf` and share its store (ADR 0055). Status becomes **Settings → Setup** (`SetupScreen`).
Diagnostics stays reachable in release builds (Settings and the setup checks link to it; the brief wants the
decision log for debugging). **Settings → Developer** (design gallery, Diagnostics with the debug test voice, and
"Start over as a new user") and the gallery are registered only when `BuildConfig.DEBUG`. The placeholder screen,
`ScreenInfo` and the "Preview screens" list are deleted. Picker, contact detail and haptic editor entries use
content keys built from the full chat key, because `ChatKey.toString()` hides
the JID and would give every chat the same key.

**Consistent chrome.** `CucutoTopBar`: a large flexible top bar without a back arrow on tab roots, a medium flexible
one with a back arrow on pushed screens, both `exitUntilCollapsed`. Home keeps its search field as its top bar.
Loading states use `LoadingIndicator`; empty states use the cuckoo door.

**Setup on Home.** Until setup has been all green once (`status_celebrated`), Home starts with the status card in a
compact form: the hero and only the checks that still fail (packs are left to the journey). Turning green
celebrates (`Feedback.Success`, the door, the burst), says the card moves to Settings, and collapses after 4 s.
Afterwards Home shows nothing, or a one-line banner when a check really breaks (module not enabled or not loaded,
hooks missing, wrong scope, WhatsApp missing). Waiting for the self-test after a reboot is not a failure there: the
module only reports with its first alert (ADR 0015), so it would nag after every reboot. The banner and the card
share bounds with the Setup screen's card. `setupCardMode` is a pure function.

**First-run journey on Home** (`homeJourney`, pure):

1. no pack: "Give your chats a voice" with **Get the demo packs** (and **Choose a file from Downloads**);
2. packs but no chats: "Your characters are ready" and **Hear the characters** (the preview-only picker);
3. packs and chats but no voice: "Give Ada a voice", one tap to the picker for the newest recent sender;
4. the first voice: the cuckoo door pops open on the new character (`Feedback.DoorOpen` with a burst in its colors),
   once (`first_voice_celebrated`), until dismissed. It is shown only while exactly one chat has a voice.

**Demo packs: one tap through Discover, a file as the fallback, nothing bundled.** "Get the demo packs" calls
`DemoPacksRequests.offer()` and switches to the Discover tab, which installs the demo bundle from the default
repository through the usual review (#214, ADR 0045). Offline, **Choose a file from Downloads** opens the system file
picker (`OpenDocument`, initial location Downloads), and the file goes through `IncomingImports`, the same path as
"Open with": the shell opens the Packs tab, which reviews and installs it (`Feedback.Success` there). The installed
snackbar offers **Give voices**, back to Home. No storage permission and no pack in any APK. Bundling the demo packs
in debug builds was rejected: it needs the packer in the Android build and still leaves release users without them.

## Consequences

- A new screen is one destination key plus one `entry`; agents working in parallel touch one line of navigation.
- New dependency: `androidx.compose.material3:material3-adaptive-navigation-suite` 1.5.0-beta02 (same version as
  Material 3, allowed by the brief for Expressive).
- `OnboardingSettings` gains `first_voice_celebrated` and `reset()` (the Developer tool).
- Tests: `AppNavigatorTest`, `AppStageTest`, `HomeJourneyTest`, `SetupCardModeTest` on the JVM, and
  `ShellNavigationTest` (Robolectric Compose UI: tabs, per-tab state and stacks, system back) plus journey cases
  in `HomeContentTest`.
- The status card's door is drawn in `primaryContainer` on a `primaryContainer` card, so only the door shows, not
  the house (seen in Robolectric renders; fixed in ADR 0050). Journey cards use
  `surfaceContainerHigh` so the whole house reads.
