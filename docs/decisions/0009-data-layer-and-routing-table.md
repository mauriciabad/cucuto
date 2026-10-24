# 0009. Data layer and the routing table published to the module

- Status: accepted (owner may override the defaults listed under "Defaults", see #86)
- Date: 2026-10-23
- Issue: #14

## Context

The brief puts contacts, assignments, repositories and the decision log in Room, and has the app write "a compact
table (contact key, character id, clip list, haptic) to libxposed Remote Preferences, which the framework stores and
serves to the module". The module runs in `system_server`, must stay free of DI and heavy dependencies, and has a
5 ms budget on the notification path (M1). The supervised M0 run found that the key is the alerting summary's
shortcut ID (`…@lid` for 1:1 chats, `…@g.us` for groups; no phone JIDs seen), that the sender `Person` still needs
a robust reader, and that SystemUI opens the clip URI. Phone JIDs may still appear on other builds or accounts.

## Decision

**Room 3 (`androidx.room3` 3.0.3, KSP) on the platform SQLite.** `AndroidSQLiteDriver` (from `sqlite-framework`,
already a Room dependency) instead of the bundled driver: no extra native library in the APK, and Robolectric's
native SQLite runs the DAO tests on the JVM. Schemas are exported to `android/core/data/schemas/` and committed.

**Tables.** `contacts` (one row per person or group; display name, group flag and policy, linked Android contact,
shuffle seed), `contact_keys` (every identity ever seen: `(package, value)` unique, kind derived from the value:
phone JID, `@lid`, group JID, E.164 number with no package, other), `assignments` (one message voice per contact by
`(packId, characterId)`, disabled clip paths as a JSON array, haptic override as pack-format haptic JSON),
`installed_packs` (id, version, path, SHA-256, size, source, bundle), `repositories` (URL, name, default, enabled,
auth type; credentials stay in `:core:repo`), `decision_log` (ring of the newest 500) and `seen_senders` (one row per
conversation, newest 1,000). Group policy lives on the contact, as in `spec/backup.schema.json`, because a
per-sender group may have no voice of its own. Ringtone assignments (M9) will get their own table.

**Migrations.** No destructive fallback. Every version bump ships a migration in `CucutoMigrations` and the
exported schema; `MigrationTest` creates every exported version with `MigrationTestHelper`, migrates it to the
current version and validates it, and checks that a schema file exists for every version.

**Routing table in its own module.** `:core:routing` is plain Kotlin/JVM with no dependencies (new
`cucuto.kotlin.library` convention plugin), used by `:core:data` to build and encode the table and by `:xposed` to
decode and query it. The model is immutable; lookups are two hash map reads. Specified in
[`docs/routing-table.md`](../routing-table.md).

**A line-based text encoding,** not JSON, protobuf or Java serialization: it needs no library in `system_server`,
decodes in one pass (about 2 ms for 500 contacts on a laptop JVM), stays readable in a preferences dump, and is
about 127 Ki characters for a heavy user (deduplicated voices, a shared URI folder per voice, a package table).
Versioned by a header; readers reject newer versions and skip unknown line types, so additive changes stay in v1.

**Transport.** The app holds the libxposed `service` 102 library, registers `XposedServiceHelper` in
`Application.onCreate` through `RoutingPublisher.start()`, and writes keys `table` and `generation` in the Remote
Preferences group `routing`. It republishes on every relevant change and when the service reconnects, skips
identical tables, and refuses tables over 256 Ki characters (Binder). The module's `RoutingTableHolder` attaches
lazily on first use, keeps a strong reference to its change listener (Remote Preferences hold listeners weakly),
decodes on change, never throws, and keeps the last readable table when a newer format appears (an updated app
before the reboot that updates `system_server`). LSPosed stops notifying the module after an app update since boot;
[ADR 0024](0024-routing-updates-after-app-update.md) adds a remote-file mirror and a nudge broadcast for that case.

**Character data comes from the installed `pack.json`** (`PackManifestCatalog`), behind a `CharacterCatalog`
interface so the importer (`:core:pack`) can provide a richer source later. A contact whose pack or character is
missing, or whose clips are all switched off, gets no route and keeps WhatsApp's tone.

**DI.** Hilt modules live in `:core:data` (`DataModule`, `DataBindings`); `:app` only injects `RoutingPublisher`.
`:xposed` and `:core:routing` stay DI-free.

### Defaults (owner may override, #86)

- A group with the default policy (one voice per group) and no voice keeps WhatsApp's tone, even if the sender has
  a voice. In a per-sender group, a sender without a voice falls back to the group's voice.
- Pack haptic mode `none` leaves WhatsApp's own vibration; a per-contact override of `none` silences it.
- The decision log keeps 500 entries and the recent-senders feed 1,000 conversations.

## Consequences

- Room 3 is young; its API differs from Room 2 (`@ColumnTypeConverter`, `Relation(parentColumns, entityColumns)`,
  suspend `Migration.migrate(SQLiteConnection)`), so Room 2 examples need translating.
- Robolectric 4.17 runs on Android 17 but needs `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` on JDK 21
  (set in `core/data/build.gradle.kts`).
- If tables ever approach the Binder limit (very large clip lists with per-clip envelopes), the escape hatch is
  libxposed remote files (`openRemoteFile`) for the payload, with the preference only carrying the generation.
- Per-clip "auto" envelopes are not produced yet (`:core:audio`); until then `auto` keeps WhatsApp's vibration.
