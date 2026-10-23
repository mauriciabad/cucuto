# 0011. Repository client: manual redirects, origin-scoped credentials, Keystore AES-GCM

- Status: accepted
- Date: 2026-10-23
- Issues: #34, #35

## Context

`:core:repo` fetches `repository.json` indexes, previews and pack files from any HTTPS host the user adds, optionally
with HTTP Basic or a bearer token ([brief](../project-brief.md#pack-repositories),
[pack format](../pack-format.md#pack-repositories)). Everything remote is untrusted: indexes can lie about sizes,
servers can loop redirects or send users elsewhere, and credentials must never leak to another host, a log or a
backup. Downloads must end in the same importer as local files ([ADR 0008](0008-pack-importer.md)).

## Decision

**OkHttp, redirects by hand.** The client turns off OkHttp's redirect following and follows 301, 302, 303, 307 and 308
itself, at most 5 times (loops end there too). Every hop must be `https://`: plain HTTP is refused before connecting,
also as a redirect target, and the default client has no cleartext connection spec. The `Authorization` header is
added per hop by Cucuto, never by an OkHttp `Authenticator`, so the client decides exactly where it goes.

**Credentials are scoped to the repository's origin** (scheme, host and port of the registered index URL). A request
to another origin gets none (a pack hosted on GitHub releases for a private index on Cloudflare), and once a redirect
leaves the current origin the rest of the chain goes without them, even if it comes back. Comparing whole origins is
stricter than "host" and costs nothing. Question #95 asks whether the owner wants an allowlist of extra hosts.

**Our own ETag cache.** Indexes are stored in `cache/repo-index/` with their `ETag` and final URL; the next fetch sends
`If-None-Match` and reuses the copy on 304. OkHttp's HTTP cache would do this too, but it does not cache responses to
requests with `Authorization` unless the server opts in, it hides whether the response was a 304, and the final URL
after redirects (the base for relative references) must be kept anyway.

**Sizes and hashes are checked three times.** Before the request (the advertised `size` against the importer's
2 GiB + 64 MiB cap), on the headers (`Content-Length` must equal the advertised size; downloads ask for
`Accept-Encoding: identity` so the length describes the file) and while streaming (the transfer stops one buffer past
the advertised size). SHA-256 is computed while streaming. The file is written as `<name>.part` and renamed only when
size and hash match (`DOWNLOAD_SIZE_MISMATCH`, `DOWNLOAD_HASH_MISMATCH`). Indexes are capped at the shared validator's
16 MiB and validated with pack-spec's `DocumentValidator` (schema, `formatVersion`, duplicate IDs) before they are
parsed or cached. Relative references resolve against the URL after redirects (RFC 3986, OkHttp `HttpUrl.resolve`).

**Downloads go through the importer.** `RepositoryDownloader` downloads to `cache/repo-download/`, then calls
`PackImporter.prepare` on the file and deletes it afterwards. The importer copies the file again; that costs one extra
copy per download but keeps one pipeline with one set of guarantees.

**Adding a repository.** A pasted URL or `cucuto://repo/add?url=…` deep link is classified by its last path segment:
`.zip` is a direct pack link, `.json` an index, anything else a folder that gets `repository.json`. No scheme means
`https://`; `http://`, other schemes and URLs with `user:password@` are refused (credentials belong in the encrypted
store). The default repository is `https://cucuto.mauri.app/repo/index.json`.

**Credentials: Android Keystore AES-256-GCM, directly.** Jetpack Security's `security-crypto` is deprecated "in favour
of existing platform APIs and direct use of Android Keystore"
([release notes](https://developer.android.com/jetpack/androidx/releases/security)), so Cucuto uses a Keystore key and
`Cipher` ([Keystore guide](https://developer.android.com/privacy-and-security/keystore)): AES-256, GCM only, random
IVs chosen by the Keystore, and `setUnlockedDeviceRequired(true)` (repositories are only contacted while the app is
open). StrongBox is not requested; the guide says most apps do not need it and it is slower. Each entry is the
credentials as JSON, encrypted with the repository URL as associated data so entries cannot be swapped, and stored in
`noBackupFilesDir/repo-credentials.json`. Android backup is off for the whole app anyway, and Keystore keys never leave
the device, so a restored file could not be decrypted. Cucuto's own backup includes credentials only when the user
opts in, inside the passphrase-encrypted backup (`:core:backup`). If decryption fails (key deleted, tampered file),
the credentials read as missing and the UI asks again.

**Never logged.** `RepositoryCredentials` and `RepositoryAccess` override `toString` with redacted values, errors
carry redacted URLs (no path or query), and nothing in the module logs.

**Testable without a device.** The Keystore sits behind `SecretKeyProvider`; JVM tests use a software AES key with the
same `AesGcmCredentialCipher` and file format. One instrumented test checks the real Keystore key (256-bit, GCM, TEE or
StrongBox, not exportable). HTTP tests run against OkHttp's MockWebServer over real TLS (a throwaway certificate for
`localhost` and `127.0.0.1`, which count as two hosts).

## Consequences

- Private repositories whose packs live on another host need the packs to be public or signed URLs, until #95 is
  answered.
- Uninstalling the app or clearing its data deletes the key; users re-enter credentials or restore an encrypted
  backup that has them.
- Seeding the default repository into `:core:data`'s table and the Discover UI (warning for unknown sources, host
  display) come with the UI work; this module provides the URL parsing, client and stores they call.
