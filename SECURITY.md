# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub:
[**Report a vulnerability**](https://github.com/mauriciabad/cucuto/security/advisories/new) (Security tab →
Advisories → Report a vulnerability). Do not open a public issue, pull request or discussion for it.

Include what is affected (app, Xposed module, website, packer, pack format), the version, steps to reproduce and, if you
can, a minimal proof of concept. Never attach copyrighted media; build a test pack from your own or CC0 files.

Cucuto is a volunteer project. We aim to acknowledge reports within a week, keep you updated in the advisory, and credit
you when the fix is released unless you prefer otherwise.

## Supported versions

Only the latest release gets security fixes. Fixes ship as a new release on
[GitHub Releases](https://github.com/mauriciabad/cucuto/releases).

## Threat model

Some things to keep in mind when looking for problems:

- **The module runs inside `system_server`.** A crash there can restart the whole system, so any input that reaches the
  hook and can make it throw, hang or allocate without bounds is a security bug, not just a crash.
- **Packs are untrusted input.** A pack file (`*.soundpack.zip`, or any `.zip`) may come from anyone. The importer must reject zip
  slip (absolute paths, `..` segments), zip bombs, oversized or too many files, unknown and executable file types,
  duplicate pack IDs in bundles, and malformed manifests. SVGs must be sanitized (no scripts, no external references).
  Media decoders (audio, images, JPEG XL) are fed attacker-controlled bytes.
- **Pack repositories are untrusted input.** A repository URL points at any static host. Index files, download URLs,
  redirects, sizes and checksums are all attacker-controlled until verified; downloads must match their SHA-256.
  Repository credentials must never leak to other hosts.
- **Backups may contain secrets.** Repository credentials are only included when the user opts in, and then the file is
  encrypted. Weaknesses in that encryption or in restore parsing are in scope.
- **The website and PWA pack creator** process user files locally in the browser and must not send them anywhere.

Out of scope: problems that require an already compromised device, issues in LSPosed, root solutions or WhatsApp
themselves, and the legality of content inside third-party packs.
