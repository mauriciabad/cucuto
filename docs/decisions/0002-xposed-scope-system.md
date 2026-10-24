# 0002. The module's scope is `system` (system_server), not `android`

- Status: accepted (owner may override, see the `question` issue linked from the PR)
- Date: 2026-10-23
- Issue: #2

## Context

The brief says the module is scoped to "System Framework (`android`)". In the legacy Xposed API, `android` meant
`system_server`. Modern modules (libxposed API 100+) declare their scope in `META-INF/xposed/scope.list`, and
there the vocabulary changed:

- LSPosed's daemon treats the scope entry **`system`** as `system_server`
  (`ConfigManager`: the modules loaded into system_server are the ones whose scope has `app_pkg_name = "system"`).
- A scope entry **`android`** is the ordinary `android` package: LSPosed maps it to the `system:ui` process
  (ResolverActivity and friends) and explicitly skips `system_server` for it
  (`ConfigManager.getAssociatedProcesses`).
- The LSPosed manager lists "System Framework" with package name `system` (`ScopeAdapter`), and reads a modern
  module's `scope.list` verbatim (`ModuleUtil`). Vector (the JingMatrix fork) documents the same convention:
  "LSPosed later made `system` the system server and left `android` as the real package, which is what every
  modern module ... mean[s]"; it swaps the two names only for legacy modules.

Sources: [LSPosed ConfigManager](https://github.com/LSPosed/LSPosed/blob/master/daemon/src/main/java/org/lsposed/lspd/service/ConfigManager.java),
[ScopeAdapter](https://github.com/LSPosed/LSPosed/blob/master/app/src/main/java/org/lsposed/manager/adapters/ScopeAdapter.java),
[ModuleUtil](https://github.com/LSPosed/LSPosed/blob/master/app/src/main/java/org/lsposed/manager/util/ModuleUtil.java),
[Vector ScopeNames.kt](https://github.com/JingMatrix/Vector/blob/master/manager-ui/src/main/kotlin/org/matrix/vector/ui/store/ScopeNames.kt).

With `staticScope=true`, a `scope.list` containing `android` would even prevent the user from selecting System
Framework, and the hooks would never load in `system_server`.

## Decision

`android/xposed/src/main/resources/META-INF/xposed/scope.list` contains exactly one line: `system`.
`module.prop` sets `minApiVersion=102`, `targetApiVersion=102`, `staticScope=true`. A unit test
(`ModuleMetadataTest`) pins all three files. "System Framework" remains the user-facing name.

## Consequences

- The intent of the brief is unchanged (only `system_server`, never WhatsApp); only the identifier differs.
- The first on-device enablement (M0/M1, `needs-device`) must confirm LSPosed shows "System Framework" as the
  module's scope and that `onSystemServerStarting` logs after a reboot.
- Docs that say "scope `android`" should be read as "System Framework".

## Owner answer (2026-10-23, #67)

> i dont understand the question or i dont have enough knowledge to answer. I dont care about backwards compatibility. I want it to work with the minimum intrusion possible, so we lower the risk of critical vulnerabilities/mistakes. Pick based on my preferences.

`system` stays: it loads the module into `system_server` only, the single process the hooks need, which is the least
intrusive scope that works. No compatibility entry for legacy Xposed (`android`) is added. The answer also became a
project-wide principle, "minimum intrusion", recorded in the brief's constraints table and in `CLAUDE.md`.

## Amendment (2026-10-24, owner answer on #145): an optional WhatsApp scope

> Not hooking the WhatsApp app is a preference, not a requirement. [...] It is pretty important that ringtones can be
> set automatically in whatsapp calls too. so i would change the rule just for this feature.

`system` stays the module's only **required** scope, and the default `scope.list` keeps WhatsApp unticked. "Never
WhatsApp" (Consequences, first bullet) becomes "never WhatsApp by default": a feature that cannot work from
`system_server`, today only automatic WhatsApp call ringtones (#215), may use an optional, opt-in WhatsApp scope that
the user enables in LSPosed after a plain explanation. How that scope is declared without being pre-selected
(`scope.list` with `staticScope=true`, or a runtime scope request through the libxposed service) is decided in #215;
`ModuleMetadataTest` follows. Rules and limits: [ADR 0051](0051-optional-whatsapp-scope.md).
