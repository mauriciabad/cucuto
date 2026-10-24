# 0045. The demo packs in one tap, from Home through Discover

- Status: accepted (defaults open for the owner in the `question` issue linked from #179)
- Date: 2026-10-24
- Issue: #179

## Context

On first run, Home's "no packs" card (the shell's "Import the demo packs" card in #153, ADR 0027) sent the user to a
file picker, so a new user had to download the demo bundle from the website first. The website publishes the default
repository at `https://cucuto.mauri.app/repo/index.json` (#94), which lists the bundle `app.mauri.demo.all` with every
demo pack. Discover (ADR 0029) already downloads repository entries with their size and SHA-256 and hands them to the
shared import review. Minimum intrusion still holds: nothing bundled in the APK, and no network without a user action.

## Decision

**One tap, the usual review.** Home's card gets "Get the demo packs" as its main button (the "Open Packs" link stays
next to it). The tap queues a request in `DemoPacksRequests` (a singleton like `IncomingRepoLinks`) and opens
Discover. Discover takes the request and:

1. shows only the demo bundle (a `focus` filter on the entry ID, shown as a "Demo packs only" chip that clears it);
2. makes sure the default repository is there and on (a user who turned it off asked for its packs by tapping);
3. reuses the index fetched when Discover opened, or waits for that fetch, so the index is requested once;
4. starts `install` for the bundle's card: download, verify, read, check, and the review sheet, where the user still
   confirms with Install.

**When the network is used.** Only after the tap: the default repository's index, then the bundle. That is the same
rule as ADR 0029 (the network is used only from Discover, on the user's action); Home itself never fetches anything.

**Offline and other failures.** A card at the top of Discover says "Couldn't get the demo packs", the reason in plain
words (`plainWords(RepoError)`), and that the file can be downloaded from the website, with Try again and "Choose a
file". A cached copy of the index is not enough (the download would fail anyway), so a failed refresh counts as
offline. "Choose a file" opens the system picker in Downloads and imports the file through the same review. A download
that fails after the index loaded ends in the import sheet's problem view, which also offers "Choose another file"
during the demo flow (other Discover installs still have no file to pick again). An index without the bundle says so
and offers the same fallback; a bundle that needs a newer pack format shows its "Needs a newer Cucuto" card.

**Shell coordination.** On `main`, Home's "no packs" card and the navigation add one `onOpenDiscover` lambda. When the
shell (#153) lands, its "Import the demo packs" card calls `DemoPacksRequests.offer()` and opens the Discover tab the
same way.

## Consequences

- A new user gets the demo packs with two taps: "Get the demo packs", then Install in the review.
- `DiscoverViewModel` takes `DemoPacksRequests`, keeps the catalog for file imports, and its state has `focus` and
  `demo`. `HomeViewModel` takes `DemoPacksRequests`.
- The bundle ID `app.mauri.demo.all` is a contract with `packs/README.md` and the CI job that builds the bundle.
- Defaults the owner can change (in the `question` issue): the focus filter instead of the whole repository, turning
  the default repository back on, and treating a stale index as offline.
