<p align="center"><img src="brand/logo/cucuto-logo.svg" alt="cucuto" width="320"></p>

# Cucuto

**Give each contact a voice.** Cucuto (koo-KOO-toe, from Catalan *cucut*, cuckoo) is an open-source Android 17 app that
plays a random clip from the character you assigned to a contact whenever they message you on WhatsApp, with that
character's own vibration. Like a cuckoo clock, it announces who's there.

- **Sound packs, not bundled content.** The app ships empty. Import `.soundpack` files or download them from pack repositories.
- **Ringtones too.** Packs can carry call ringtones with variants (day, night, seasons…).
- **Make your own.** Build packs in the app or on the website, no tools needed.
- **Feels like a game.** Sound, motion and haptics on every interaction, built on Material 3 Expressive.

> [!IMPORTANT]
> Cucuto needs **Android 17, root and LSPosed (libxposed API 102)**. It runs as a tiny module inside `system_server` and
> never touches the WhatsApp process. No content ships with the app; you are responsible for the packs you import.

## Repository

| Path | What |
| --- | --- |
| [`android/`](android) | The app and its Xposed module (Kotlin, Jetpack Compose) |
| [`web/`](web) | Website, default pack repository and PWA pack creator (SvelteKit) |
| [`tools/packer/`](tools/packer) | Command-line tool to build packs, bundles and repository indexes |
| [`spec/`](spec) | JSON Schemas and golden test packs |
| [`packs/`](packs) | CC0 demo packs |
| [`brand/`](brand) | Logo, icons and visual language |
| [`docs/`](docs) | [Project brief](docs/project-brief.md), pack format, guides, decisions |

## License

Code is [GPL-3.0-or-later](LICENSE). Demo packs are [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/).
