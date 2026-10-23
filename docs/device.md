# Device facts

Reference facts for the owner's development phone, gathered read-only over adb on 2026-10-23
(issue #9). Nothing in this file is personal data: it is build identity, framework versions and
paths. Notification contents, contacts and the module scope list are deliberately excluded.

## Hardware and ROM

| Fact | Value |
| --- | --- |
| Model | Pixel 10 Pro |
| Device / product | `blazer` |
| Build fingerprint | `google/blazer/blazer:17/CP3A.261005.005/16271449:user/release-keys` |
| Build ID | `CP3A.261005.005` |
| Android release | 17 |
| SDK | 37 (`ro.build.version.sdk_full` = **37.1**) |
| Security patch | 2026-10-05 |
| Build type | `user` (release-keys) |
| ABI | `arm64-v8a` |
| Kernel | `6.6.127-android15-8-g91d825611104-ab16252310-4k` |

Note the **minor SDK**: the device is 37.1. This matters for haptics — see below.

## Root and hooking framework

| Fact | Value |
| --- | --- |
| Root manager | KernelSU, `ksud 3.4.0` (uapi 4); `su -c` works over adb |
| Xposed framework | **LSPosed 2.2.1 (7912)**, the `lsposed.zip` revival fork |
| Framework flavour | Zygisk module `zygisk_lsposed` under KernelSU |
| **libxposed API level** | **102** — matches the level the brief settled on |
| Developer mode | off |
| Safe mode | off |
| system_server | hooked by LSPosed (`systemServerRequested: true`) |

### Paths under `/data/adb` (names only)

```
bindhosts  boot-completed.d  boot_hash  ksu  ksud  lspd  metamodule
modules  modules_update  mountify  service.d  tricky_store  zygisk-detach  zygisksu
```

LSPosed's own state lives in `/data/adb/lspd/`:

- `config/modules_config.db` — SQLite; the tables that matter are `modules`,
  `modules_state(module_pkg_name, user_id, enabled, scope_request_blocked)` and
  `scope(module_pkg_name, app_pkg_name, user_id)`. Setting `modules_state.enabled = 0` disables a
  module; a row in `scope` with `app_pkg_name = 'android'` is System Framework scope.
- `config/dex2oat_service.db`, `log/`, `monitor`, `zn_monitor`.

The framework module directory is `/data/adb/modules/zygisk_lsposed/`, which also ships an
**`lspctl`** CLI:

```
adb shell su -c '/data/adb/modules/zygisk_lsposed/lspctl status'
adb shell su -c '/data/adb/modules/zygisk_lsposed/lspctl --json status'
```

`lspctl` prints the framework version, API level, developer/safe mode and system_server
connection. Its own help says it is for interactive debugging and is **not a stable control
protocol**, so cucuto must never depend on it at runtime — it is a human/agent debugging and
recovery aid only. Enable/disable and scope subcommands require LSPosed developer mode.

## Relevant apps

| Package | Version | Role |
| --- | --- | --- |
| `com.whatsapp` | 2.26.42.9 | the app whose notifications cucuto re-voices |
| `com.waenhancer` | 2.0.4 | WaEnhancer X; hooks `com.whatsapp`, must keep working |

cucuto scopes to `android` only and never touches either of these processes.

## Notification sound player

`NotificationAttentionHelper.playSound` hands the URI to `IRingtonePlayer`, which is hosted by
**SystemUI**:

| Fact | Value |
| --- | --- |
| Process | `com.android.systemui` |
| Shared user | `android.uid.systemui` |
| UID on this device | **10253** |

The numeric UID is assigned at install time and is **not stable across devices or reinstalls**, so
a provider allowlist must match the *package name* / shared user, never the number. See
`docs/m0-report.md`.

## Haptics API availability

Checked by listing `android/os/VibrationEffect$*` in the device's `framework.jar` and in the
installed SDK platforms.

| Class | On device (37.1) | `android-37.0` SDK | `android-37.2` SDK |
| --- | --- | --- | --- |
| `VibrationEffect.Builder` | **yes** | no | yes |
| `VibrationEffect.Preset` | **yes** | no | yes |
| `VibrationEffect.Envelope` | **yes** | no | yes |
| `VibrationEffect.BasicEnvelopeBuilder` | **yes** | yes | yes |
| `VibrationEffect.WaveformEnvelopeBuilder` | yes | — | — |
| `VibrationEffect.Composition` | yes | yes | yes |

So the brief's **primary** haptics API is present on the owner's build and no fallback is needed
at runtime — but the project's current compile platform (`android-37.0`, per `CLAUDE.md`) does not
expose `Builder`. To reference it at compile time the Android module needs `compileSdkMinor = 2`
(platform `android-37.2`, already installed). The M0 spike avoids the question entirely by
resolving the Builder reflectively.

`Preset` constants available on this build: `PRESET_NOOP(0)`, `PRESET_CLICK(1)`, `PRESET_THUD(2)`,
`PRESET_SPIN(3)`, `PRESET_QUICK_RISE(4)`, `PRESET_SLOW_RISE(5)`, `PRESET_QUICK_FALL(6)`,
`PRESET_TICK(7)`, `PRESET_LOW_TICK(8)`. Built with `Preset.create(int presetId, float scale)`.

## Build host

| Tool | Version |
| --- | --- |
| JDK | Homebrew `openjdk@21` (21.0.13) |
| Gradle | 9.8.2 |
| AGP | 9.4.1 — **Kotlin support is built in**; the `org.jetbrains.kotlin.android` plugin is rejected |
| Android SDK platforms | `android-36`, `android-37.0`, `android-37.2` |
| Build tools | 35.0.0, 36.0.0, 37.0.0 |
| jadx | 1.5.6 |

## How these were gathered

All read-only, no device state changed:

```
adb shell getprop
adb shell uname -a
adb shell pm list packages
adb shell dumpsys package <pkg> | grep versionName
adb shell su -c 'ls /data/adb/'
adb shell su -c '/data/adb/modules/zygisk_lsposed/lspctl --json status'
adb pull /system/framework/services.jar     # Google's code, not owner data
adb pull /system/framework/framework.jar
```

`spikes/m0/run-on-device.main.kts preflight` re-runs the live subset of these at any time.
