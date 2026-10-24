<p align="center"><img src="brand/logo/cucuto-logo.svg" alt="cucuto" width="320"></p>

# Cucuto

[![ci](https://github.com/mauriciabad/cucuto/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/mauriciabad/cucuto/actions/workflows/ci.yml)

**Give each contact a voice.** Cucuto (koo-KOO-toe, from Catalan *cucut*, cuckoo) is an open-source Android 17 app that
plays a random clip from the character you assigned to a contact whenever they message you on WhatsApp, with that
character's own vibration. Like a cuckoo clock, it announces who's there.

- **Sound packs, not bundled content.** The app ships empty. Import `.soundpack.zip` files or download them from pack repositories.
- **Ringtones too.** Pick a contact's message sound, then their phone ringtone: a song from a pack, Android's default,
  or theirs left as it is. Cucuto can always give the old ringtone back.
- **Rules you can read.** Songs with several versions (day, night, seasons, holidays) follow the pack's rules, shown in
  plain words, or rules you write for one contact.
- **Clips with timing.** Packs can greet the first message of the day, answer a burst of messages, hide rare easter
  eggs and play night or holiday clips, all decided on the phone with no battery cost.
- **Chats before they write.** Add people from your contacts; their voice plays from their second message.
- **Easy to pick.** Big packs fold into sections, with search and a "Surprise me" dice in both pickers.
- **Make your own.** Build packs in the app or on the website, no tools needed, or with the `packer` CLI.
- **Feels like a game.** Sound, motion and haptics on every interaction, built on Material 3 Expressive.

> [!IMPORTANT]
> Cucuto needs **Android 17, root and LSPosed (libxposed API 102)**. It runs as a tiny module inside `system_server` and
> never touches the WhatsApp process. No content ships with the app; you are responsible for the packs you import.

<!-- Screenshot slot: app screenshots made from the demo packs go here (#41). -->

Cucuto is an early test version: see the [changelog](CHANGELOG.md) for what each release brings. The website is [cucuto.mauri.app](https://cucuto.mauri.app).

## Install

1. Install the APK from [GitHub Releases](https://github.com/mauriciabad/cucuto/releases).
2. In the LSPosed manager, enable **Cucuto** with the **System Framework** scope only.
3. Reboot.
4. Open Cucuto: the setup guide and the status card check that everything works.
5. Import a pack and give a contact a character.

Details, troubleshooting and recovery: [install guide](docs/install.md) (also on the
[website](https://cucuto.mauri.app/install/)). Cucuto never checks for updates itself: watch the releases, or let [Obtainium](https://github.com/ImranR98/Obtainium) do it (the website's Install page has a one-tap setup). Where else it is distributed: [docs/distribution.md](docs/distribution.md). Cucuto isn't on Google Play
([ADR 0080](docs/decisions/0080-no-google-play.md)).

## Build

You need JDK 21, the Android SDK, Rust with cargo-ndk (for the JPEG XL decoder) and [Bun](https://bun.com). Full setup
in [CONTRIBUTING.md](CONTRIBUTING.md#building).

```sh
(cd android && ./gradlew build)                    # app and module: debug + release APKs, Lint, ktlint, unit tests
(cd web && bun install && bun run build)           # website and PWA, in web/build/
(cd tools/packer && ./gradlew build installDist)   # packer CLI, in tools/packer/cli/build/install/packer/
```

## Repository

| Path | What |
| --- | --- |
| [`android/`](android) | The app, its Xposed module and the `:core:*` libraries (Kotlin, Jetpack Compose) |
| [`web/`](web) | Website, default pack repository and PWA pack creator (SvelteKit) |
| [`tools/packer/`](tools/packer) | Command-line tool to build packs, bundles and repository indexes |
| [`shared/pack-spec/`](shared/pack-spec) | The pack format rules in Kotlin, shared by the app and the packer |
| [`spec/`](spec) | JSON Schemas, error codes and golden test packs |
| [`packs/`](packs) | CC0 demo packs |
| [`brand/`](brand) | Logo, icons, colors, motion and sound language |
| [`docs/`](docs) | [Project brief](docs/project-brief.md), [pack format](docs/pack-format.md), [install guide](docs/install.md), [decisions](docs/decisions) |
| [`spikes/`](spikes) | Throwaway prototypes behind some decisions |

## Make packs

A pack is a plain zip with a `pack.json` manifest and characters, each with a sprite, a pool of clips, an optional
ringtone and a vibration.

- [For pack authors](https://cucuto.mauri.app/authors/) on the website: the short version.
- [Pack format](docs/pack-format.md): the full rules, with JSON Schemas in [`spec/`](spec).
- [packer](tools/packer/README.md): build a pack from a folder with a `pack.yaml`, validate it, bundle packs and
  publish your own pack repository.
- [`packs/`](packs): two CC0 demo packs to copy from.

## Built by AI agents

Cucuto is built by AI agents (Claude) working from GitHub issues. The owner sets the scope, answers questions and has
the final say; the agents write the code, docs and reviews. Everything an agent writes on GitHub is labeled as AI
work. Human contributors are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Agents (or people) picking up a small
change start with [docs/maintaining.md](docs/maintaining.md) and the [decision index](docs/decisions/README.md).

## License

Code is [GPL-3.0-or-later](LICENSE). Demo packs are [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/).
Security reports: [SECURITY.md](SECURITY.md).
