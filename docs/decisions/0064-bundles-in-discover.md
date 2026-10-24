# 0064. Bundles in Discover: one card with its packs inside

- Status: accepted
- Date: 2026-10-24
- Issue: #247 (amends [ADR 0029](0029-discover-screen.md) and [ADR 0045](0045-demo-packs-in-one-tap.md))

## Context

The default repository lists each demo pack on its own and the bundle `app.mauri.demo.all` with every demo pack, so
Home's "Get the demo packs" can install them in one tap (#179, ADR 0045). Discover (#36, ADR 0029) showed every index
entry as the same kind of card. The bundle card had the first pack's cover (CI built the bundle with
`packs/chiptune-critters/cover.svg`) and the first pack's characters first, so it looked like a second Chiptune critters
under another name. The owner reported it (2026-10-24, "in discover the chiptune critters is duplicated as cucuto demo
pack"). Search matched the same pack twice (a frog's name found both cards), and the bundle's badge only knew about
packs installed from that bundle, not a member installed on its own.

The index already tells bundles apart: a bundle entry has `type: "bundle"` and lists its packs by ID, name and version,
with their character previews ([pack format](../pack-format.md#pack-repositories)). The app needs nothing new from the
index, so the schema, the fixtures and `packer index` stay as they are.

## Decision

**A bundle is one card with its packs inside.** The card says it is a bundle ("Bundle · 2 packs"), shows its own cover,
name, author, version, size and description, then one row per pack: the pack's cover, name and version, its characters
to tap and hear, and its own badge (Installed, Update available). Under the rows, the card's badge says how many of its
packs are installed ("1 of 2 installed") and the button installs the bundle ("Install all", "Update" or "Install
again"), through the usual review, where the user still picks which packs to install.

**Each pack shows once.** A pack entry whose ID is also inside a bundle of the same index is not a card of its own: it
is that bundle's row, with a small Install or Update button that downloads the pack alone (a pack inside several
bundles is a row in each). Packs that only exist inside a bundle are rows without a button. Different repositories are not merged: a pack two
repositories publish shows in both sections, as before.

**Install state per pack.** A row's pack is installed when a pack with its ID is, whatever file it came from. Its badge
compares the installed version with the pack listed on its own when there is one (the newest the repository has), else
with the version inside the bundle. The bundle's state comes from its packs and the versions inside it: Installed when
every pack is there in that version or newer, Install while any is missing, Update when all are there and one is older.
This replaces ADR 0029's "a bundle entry when packs installed from it (same bundle ID) are", which missed packs
installed alone and called a bundle installed when only one of its packs was.

**Search, tags and counts.** A bundle card matches a search or a tag when the bundle or any of its packs does (names,
author, description, tags, character names), and then shows all its packs. A section's count is the number of cards, so
a pack is never counted twice. The tag list still comes from every index entry.

**The demo packs (#179).** Home's request still focuses Discover on `app.mauri.demo.all` and installs it; the focused
card is now the bundle with both demo packs inside. CI builds the demo bundle with its own cover
(`packs/demo-bundle-cover.svg`, two fanned cards) as version 1.1.0, so it no longer looks like its first pack. The
website's index shows the new cover after the next `packs-v*` release.

## Consequences

- The default repository shows one demo card instead of three, and the frog is found once.
- `PackCard` has `members` (rows with their own state and, when listed alone, their own card) instead of
  `bundlePacks`, and bundle cards no longer carry a flat character list.
- A repository that only lists a bundle still works: the rows have no buttons and the bundle installs as before.
- Installing a pack alone from a bundle row uses the standalone entry's file, so its origin and update badge are the
  same as from a pack card.
