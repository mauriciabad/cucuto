# 0051. An optional, opt-in WhatsApp scope, only for features that need it

- Status: accepted (owner, #145). Amends the "never hook the WhatsApp process" rule in `CLAUDE.md`, the brief,
  [ADR 0002](0002-xposed-scope-system.md) and [ADR 0023](0023-call-ringtones-native-first.md). The first feature
  that uses it, WhatsApp call ringtones, is spiked in #215.
- Date: 2026-10-24
- Issue: #145

## Context

Since the kickoff, the module has run only in `system_server` (scope `system`, ADR 0002) and the rules said "never
hook the WhatsApp process", for two reasons: coexistence with WaEnhancer X, which hooks WhatsApp, and the owner's
"minimum intrusion" principle (#67). The M9 spike ([report](../m9-report.md) §5) then found that WhatsApp plays its
call ringtone itself, inside its own process: Telecom does not ring for its self-managed calls, and its call
notification channel has no sound. So v1 could only guide the user to WhatsApp's per-chat call ringtone by hand (#145
default).

The owner answered on #145 (2026-10-24, verbatim in the [owner log](../owner-log.md)):

> Not hooking the WhatsApp app is a preference, not a requirement. My main concern is that users may think the app
> could spy them or something, that was why the no hooking rule (in adition to being compatible with WA Enhancer X).
> but if we inform the user why we need each permision they will understand, and ultimately they dont have to give
> the permission if they wont use ringtones. [...]
>
> It is pretty important that ringtones can be set automatically in whatsapp calls too. so i would change the rule
> just for this feature.

## Decision

1. **The default does not change.** The module's required scope is System Framework only (`system`). Every feature
   that can work from `system_server` (or with no hook at all) must, and Cucuto works fully with only that scope.
2. **An optional WhatsApp scope is allowed only for features that cannot work otherwise.** Today that is one
   feature: automatic WhatsApp call ringtones. Adding another feature to this list needs a new ADR and a `question`
   issue for the owner. The scope covers `com.whatsapp` (and `com.whatsapp.w4b` when the feature supports Business)
   and nothing else.
3. **Opt-in, explained, and the feature works only with it.** The scope is off by default; nothing asks for it until
   the user turns on the feature that needs it. Before that, a full screen (not a dialog, owner #166) says in plain
   words, in English and Spanish:
   - what is hooked inside WhatsApp and why (for example, "which ringtone file WhatsApp's call ringer opens");
   - what is never read: messages, chats, media, call content, contacts beyond what the feature uses;
   - that the code is open source, with a link to the exact hook file, so the user (or their AI agent) can read it;
   - that the feature simply stays off, and everything else keeps working, if they don't enable the scope;
   - how to enable it in LSPosed, and how to remove it again.
   The app checks whether the scope is active and shows the feature as unavailable (with the guided fallback, ADR
   0023) when it is not. Turning the feature off tells the user they can remove the scope.
4. **Minimum intrusion inside WhatsApp.** Hook as few methods as possible, as late as possible, and substitute a
   value downstream of WhatsApp's own decisions (for example, the ringtone URI it is about to play) rather than
   changing what WhatsApp decides. Prefer stable framework methods called from WhatsApp's process over WhatsApp's
   obfuscated classes. Read nothing of messages. Identify the caller in `system_server` where possible (WhatsApp's
   incoming-call notification already passes through the existing hook), so the WhatsApp side only needs the
   answer. No network, no files written by the hook, no persistence inside WhatsApp's data.
5. **Never break WhatsApp.** Every WhatsApp-side hook catches everything and falls back to WhatsApp's own behaviour
   (its own ringtone). Nothing is done when Cucuto's global switch is off.
6. **Coexistence with WaEnhancer X is a requirement**, verified on the owner's phone (`needs-device`) before the
   feature ships: no hook clashes, WhatsApp and WaEnhancer X stay healthy, both features keep working.
7. **How the scope is declared is part of the spike (#215).** `module.prop` has `staticScope=true` and `scope.list`
   holds only `system`. Listing WhatsApp in `scope.list` might make LSPosed pre-select it, which would break
   "opt-in"; the alternative is libxposed's service scope request, made by the app when the user turns the feature
   on. The spike picks the option that keeps WhatsApp unticked until the user chooses it, and updates
   `ModuleMetadataTest`.

## Consequences

- `CLAUDE.md`'s ground rule, the brief's constraints table, the ringtone table, M9 and the risk table now describe the
  default plus this exception.
- User-facing copy that says Cucuto "never hooks the WhatsApp app" (`README.md`, `docs/install.md`, the website FAQ
  and install steps in `web/src/lib/i18n/`) stays true until the feature ships; the PR that ships it updates it to
  "only if you turn on WhatsApp call ringtones" (tracked in #215).
- Users who never enable the scope get exactly the v1 behaviour: notification voices from `system_server`, phone
  ringtones through Android contacts, and the guided per-chat ringtone for WhatsApp calls.
- The `system_server` rules (never throw, degrade to WhatsApp's default tone) are unchanged and now also apply to the
  WhatsApp process.
- **Outcome of spike #215** ([ADR 0052](0052-whatsapp-call-ringtones.md), [report](../whatsapp-ringtone-spike.md)): the hook is one framework method, `RingtoneManager.getRingtone(Context, Uri)`, and the caller is read from WhatsApp's own call notification (name only). `scope.list` lists `system` and `com.whatsapp` (plus `com.whatsapp.w4b`: the feature covers WhatsApp Business too, owner #229), which LSPosed recommends but never ticks automatically, so point 7 resolves to listing WhatsApp and requesting it at runtime after the explainer. A device run (#228) is still pending.
