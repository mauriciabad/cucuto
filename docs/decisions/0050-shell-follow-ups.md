# 0050. Shell follow-ups: the door on the status card, the Settings badge, restore on first run

- Status: accepted (defaults open for the owner in #232)
- Date: 2026-10-24
- Issues: #178, #180

## Context

The app shell (#153, ADR 0027) left follow-ups. The cuckoo house was invisible on the status card: the card is
`primaryContainer` and the door drew its house in `primaryContainer` too (#178). When setup broke after being green
once, only Home showed it, as a one-line banner; nothing in the navigation bar did (#180). Someone moving to a new
phone had to find "Restore from backup" six sections down in Settings, although the restore flow (#182, ADR 0028)
installs the packs and the voices in one go. On first run, Home's setup card pushed the journey's "Get the demo
packs" below the fold of a typical phone (360 × 800 dp), as the `home-first-run` golden showed.

Guidelines read: Material 3 [color roles](https://m3.material.io/styles/color/roles) (each `on…` role is the one
guaranteed to contrast with its container), WCAG 2.1 SC 1.4.11 (3:1 for graphics), the Material 3
[badge](https://m3.material.io/components/badges/guidelines) guidelines (a small badge for a status, no number) and
`NavigationSuiteItem(badge = …)` in the adaptive navigation suite, and the brand's logo variants (`brand/README.md`).

## Decision

**The door on a filled card.** `CuckooDoor` takes `colors: CuckooDoorColors`. `CuckooDoorDefaults.colors()` keeps
the logo's look for surfaces. `CuckooDoorDefaults.onContainerColors()` is for a door on a `primaryContainer` card:

- the house takes `onPrimaryContainer`, the one role every scheme makes contrast with the card: brand light, dark,
  medium and high contrast, the green "ready" character scheme, and dynamic color;
- the opening (and the knob and the rim) is cut out of the house and shows the card, like the brand's mono mark;
- the door is 60 % of the way from the house to the card, like the apricot door of the "on color" mark, which sits
  between its cream house and the tangerine behind it;
- the sound arcs take the house color.

`CuckooDoorColorsTest` checks it on the brand schemes, the leaf scheme and `SchemeTonalSpot` (what Android builds
from the wallpaper) for seven wallpaper seeds, light and dark. The house and the opening stay at least 4.6:1 against
the card, the door at least 2.3:1 against the house.

**The Settings badge.** `StatusReports` now holds the status pipeline (probes, the grace for "no service" after
launch, the pull check of ADR 0048) that `StatusViewModel` had. `SetupAttentionViewModel` uses it at shell level: it
lives as long as the activity and re-probes when the app resumes, never on a tab switch. The Settings tab wears a
small badge (with "Setup needs attention" for TalkBack) while `StatusReport.needsAttention` is true. That is the same
rule as Home's banner, so it stays calm:

- **lit:** the module is not enabled, needs a reboot (including an update that lost the LSPosed service, #209), runs
  with missing hooks, or the framework is unsupported; System Framework is missing from the scope or other apps are
  in it; WhatsApp is missing or disabled;
- **not lit:** no packs (Home's journey offers them), the self-test pending after a reboot, a scope LSPosed does not
  report, and an update whose module code waits for the next reboot while voices keep working.

The badge follows the rule before setup was ever green too, since the Home card and Settings → Setup show the same
thing. Settings shows Home's banner ("Cucuto needs a hand") as its first item while the badge is lit, so the badge
leads to a fix instead of to a long list. Its bounds are shared with the Setup card.

**Restore on first run.** Home's first card ("Give your chats a voice") gets **Restore a backup** next to "Pick a
file from Downloads". `RestoreHost` (in `ui/settings/restore/`) owns the restore view model, the file picker and the
full-screen restore flow, so Settings and Home share it. From Home, "keep automatic backups in this file" goes
through `HomeJourneyViewModel` to the same `AutoBackupControl.chooseFile` that Settings uses.

**The journey comes first on Home.** The journey's card now sits above the setup card. The next thing to do (get the
demo packs, give the first voice) is on screen without scrolling; the setup card follows right below, so its hero
still shows. `HomeFirstRunFoldTest` checks it at 360 × 800 dp with the 80 dp navigation bar.

## Consequences

- `CuckooDoor` and `CuckooDoorStill` take a `colors` parameter; existing callers keep the default.
- `StatusViewModel` takes `StatusReports` instead of the eight sources. Its state and celebration logic are unchanged.
- `SettingsScreen` takes `setupNeedsAttention`, fed by `cucutoEntries` from the shell, instead of a new
  `CompositionLocal`.
- Goldens: `home-first-run` (journey first, the visible house), the Setup screen as `setup` (the reboot card and the
  badge) and `setup-ready` (the open door on the green card). Discover's golden now shows the shell's navigation bar
  like the other tabs.
- Small fix: the status list separator (`", "`) lost its space, because Android trims unquoted resource strings
  ("Hooks: playSound,playVibration").
