# Security policy

Cucuto is a root app: its LSPosed module runs inside `system_server`, the most privileged process of Android userspace.
We take bugs that can crash, hang or abuse that process seriously.

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub:
[**Report a vulnerability**](https://github.com/mauriciabad/cucuto/security/advisories/new) (Security tab →
Advisories → Report a vulnerability). Do not open a public issue, pull request or discussion for it.

Include what is affected (app, Xposed module, website, packer, pack format), the version, your Android build and
LSPosed version, steps to reproduce and, if you can, a minimal proof of concept. Never attach copyrighted media; build a
test pack from your own or CC0 files. Redact chat keys, names and numbers from logs.

Cucuto is a volunteer project. We aim to acknowledge reports within a week, keep you updated in the advisory, and credit
you when the fix is released unless you prefer otherwise.

## Supported versions

Only the latest release gets security fixes. Fixes ship as a new release on
[GitHub Releases](https://github.com/mauriciabad/cucuto/releases). Install Cucuto only from there (or build it
yourself); there is no other official source.

## Threat model

What the design relies on, and what to look for:

- **The module runs inside `system_server`.** A crash there restarts Android or stops the phone from booting, so any
  input that reaches the hook and can make it throw, hang or allocate without bounds is a security bug, not just a
  crash. Every hook must catch everything and fall back to WhatsApp's own tone.
- **Narrow scope.** The module's scope is System Framework only (`system`, with `staticScope=true`). It must never load
  into WhatsApp or any other app. A way to widen its scope, or to make it act on another app's notifications in a way
  the user did not choose, is in scope.
- **The app's exported providers.** `ClipProvider` (`app.mauri.cucuto.clips`) serves clip files read-only to uid 1000
  and `com.android.systemui` only. `ModuleEventProvider` (`app.mauri.cucuto.events`) accepts decision reports from
  uid 1000 only. Any other app reading clips, injecting decisions or reaching other files through them is a bug.
- **The routing table.** The app publishes it to the module through LSPosed's Remote Preferences. The module treats it
  as untrusted: a malformed or oversized table must not hurt `system_server`.
- **Privacy.** The module reads the sender of each WhatsApp notification. Its log never contains chat keys, names,
  numbers or message text; the app keeps decisions in its private storage, and Android backup is off. A leak of that
  data to logs, other apps or the network is in scope.
- **Packs are untrusted input.** A pack file (`*.soundpack.zip`, or any `.zip`) may come from anyone. The importer must
  reject zip slip (absolute paths, `..` segments), zip bombs, oversized or too many files, unknown and executable file
  types, duplicate pack IDs in bundles, and malformed manifests. SVGs must be sanitized (no scripts, no external
  references). Media decoders (audio, images, JPEG XL) are fed attacker-controlled bytes.
- **Pack repositories are untrusted input.** A repository URL points at any static host. Index files, download URLs,
  redirects, sizes and checksums are all attacker-controlled until verified; downloads must match their SHA-256.
  Repository credentials must never leak to other hosts.
- **Backups may contain secrets.** Repository credentials are only included when the user opts in, and then the file
  is encrypted. Weaknesses in that encryption or in restore parsing are in scope.
- **The website and PWA pack creator** process user files locally in the browser and must not send them anywhere.

Out of scope: the risks that come with root and LSPosed themselves (weaker Android security, Play Integrity),
problems that need an already compromised device or a malicious root manager, issues in LSPosed, root solutions or
WhatsApp, and the legality of content inside third-party packs.

If a Cucuto bug leaves your phone unable to boot, the [install guide](docs/install.md#if-the-phone-does-not-boot) has
recovery steps.
