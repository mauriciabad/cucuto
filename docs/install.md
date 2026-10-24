# Install guide

This is the website's [Install guide](https://cucuto.mauri.app/install/) ([Spanish](https://cucuto.mauri.app/es/install/))
plus the details that only matter on GitHub: building the APK before the first release, recovery and bug reports. The
website copy lives in `web/src/lib/i18n/en.ts` and `es.ts`; change both pages and this file together.

> [!WARNING]
> Cucuto runs a small module inside `system_server`, the core of Android. A bug there can crash Android or stop the
> phone from booting. Read [If the phone does not boot](#if-the-phone-does-not-boot) before you enable it.

## Before you start

- **Android 17.** Settings › About phone shows the version. Cucuto does not run on Android 16 or older.
- **Root**, for example with Magisk, KernelSU or APatch. Cucuto does not root your phone for you.
- **LSPosed** that loads modern modules (libxposed API 102), installed and working. Developed on LSPosed 2.2.1 under
  KernelSU ([device facts](device.md)).
- **WhatsApp or WhatsApp Business**, with notifications on and its own tone and vibration left on.

Root and LSPosed weaken Android's security and can break banking apps, Play Integrity and your warranty. An Android
update can move the code Cucuto hooks; WhatsApp's own tone then plays until Cucuto is updated. To pick a voice, the
module reads who sent each WhatsApp notification. That stays in Cucuto's private storage on the phone.

## Steps

1. **Download and install the APK.** Get the latest `.apk` from
   [GitHub Releases](https://github.com/mauriciabad/cucuto/releases) and open it on the phone. Android asks you to
   allow installs from that app (your browser or file manager) the first time.
2. **Enable the module in LSPosed.** Open the LSPosed manager, go to **Modules**, turn on **Cucuto** and check that its
   scope is **System Framework** only. Cucuto asks for exactly that scope, so it is usually ticked already. Don't add
   WhatsApp: Cucuto never hooks the WhatsApp app.
3. **Reboot.** The module loads into `system_server` at boot, so a full reboot is needed after enabling it, changing
   its scope or updating Cucuto.
4. **Check the status card.** Open Cucuto. On first launch a setup guide walks you through these steps: what Cucuto
   does, requirements and risks, enabling the module, rebooting and a test notification. You can skip it and reopen it
   later from Settings › Setup › **Setup guide**. The status card on Home then checks that the module is active, the LSPosed scope is
   right, WhatsApp is installed and at least one pack is imported. Each failing check tells you how to fix it.
5. **Import a pack and assign characters.** Import a `.soundpack.zip` file or pick one from the
   [free packs](https://cucuto.mauri.app/packs/), then choose a chat, tap a character and, if you like, a ringtone
   (step 2 of the same flow: a song from a pack, **Android default** or **Leave as is**). Someone who hasn't written
   since you installed Cucuto? Add them with **Add a chat from contacts**. Keep WhatsApp's own tone and vibration on:
   Cucuto replaces them per contact. To import, open **Packs** › **Import a pack**, or open or share a
   `.soundpack.zip` file with Cucuto from any app.

## Test it

In Cucuto, open Settings › **Diagnostics** and tap **Post test notification** (the setup guide's last step and the
status card offer the same button). With the module active you hear a two-tone rising chirp and feel a short click-tick
instead of the default sound. The decision shows up in Diagnostics, and the module check turns green, within about
10 seconds.

Right after a reboot the module check reads "Enabled, but the module has not reported since this boot" until the first
alert: the module only reports when it handles a notification, so post the test notification once.

## Updates

Cucuto never checks for updates and never goes online to look for them. New versions appear on
[GitHub Releases](https://github.com/mauriciabad/cucuto/releases); [Obtainium](https://github.com/ImranR98/Obtainium)
can install them for you (the website's [Install page](https://cucuto.mauri.app/install/#updates) has a one-tap
setup). After every update, reboot: the module loads the new version at boot.

Every Cucuto APK is signed with the same certificate, SHA-256 `6ded92bf…` (the full value is on every release and on
the Install page), so a copy from GitHub and a copy from Obtainium update each other without uninstalling. Cucuto
isn't on Google Play ([ADR 0080](decisions/0080-no-google-play.md)).

## Troubleshooting

| What you see | What to do |
| --- | --- |
| Module check: "Not enabled" | Turn on Cucuto in LSPosed with the System Framework scope, then reboot. |
| "Enabled in LSPosed, but not loaded into the system yet" | Reboot. |
| "Cucuto was updated since the last boot" | Reboot: `system_server` still runs the old module. |
| "These hooks could not be installed on this Android build" | Your Android build moved the code Cucuto hooks. WhatsApp's own tone plays. [Open a bug](https://github.com/mauriciabad/cucuto/issues/new?template=bug_report.yml) with your build number. |
| Scope check fails | In LSPosed, open Modules › Cucuto, tick only System Framework, untick anything else and reboot. |
| A contact plays WhatsApp's normal tone | That is the safe fallback: the contact has no character yet, the **Cucuto voices** switch is off, or the module isn't active. Diagnostics shows the decision for each recent notification. |
| No sound at all | Cucuto only replaces a sound that was going to play. Check that the chat isn't muted, Do Not Disturb is off and WhatsApp's tone isn't silent. |
| "Wait N seconds" after tapping the test | Android mutes notifications an app posts less than 10 seconds apart, so Cucuto waits. Within a minute of the last test the chirp plays quieter. |
| The test plays the default sound | The module is not active: check the steps above. |
| The test makes no sound | Allow Cucuto's notifications in the system settings, and keep the sound of its Self-test channel on. |

LSPosed's own log (LSPosed manager › Logs) shows whether the module loaded: look for
`Hooks installed: [playSound, playVibration]; missing: []`.

## If the phone does not boot

Try these in order:

1. **The phone works.** Disable Cucuto in LSPosed and reboot, or uninstall the app (`adb uninstall app.mauri.cucuto`)
   and reboot. LSPosed drops a module whose app is gone.
2. **The phone boots but the LSPosed manager is unreachable.** With adb and root, disable LSPosed itself:
   `adb shell su -c 'touch /data/adb/modules/zygisk_lsposed/disable'`, then `adb reboot`. The folder name depends on
   your LSPosed build (`adb shell su -c 'ls /data/adb/modules'`). Delete the file and reboot to turn LSPosed back on.
3. **No adb.** Boot into your root manager's safe mode (usually by pressing Volume Down during boot; check its
   documentation). It turns off every module, LSPosed included. Then go back to step 1.
4. **Last resort.** Flash the factory image for your exact build.

## Turn it off or uninstall

To pause Cucuto, turn off the **Cucuto voices** switch (in Settings, or the Quick Settings tile): every chat goes back to
WhatsApp's tone right away. To remove it completely, disable the module in LSPosed, reboot and uninstall the app.
Uninstalling deletes your imported packs and assignments.

## Debug builds

To test unreleased changes, install a debug build. It has another signature, so a release build must be uninstalled first (back up before: uninstalling deletes your
packs and voices). Either
download the `cucuto-debug-apk` artifact from a recent
[ci run on `main`](https://github.com/mauriciabad/cucuto/actions/workflows/ci.yml?query=branch%3Amain) that changed
the app (needs a GitHub account; artifacts expire after 14 days), or build it (setup in [CONTRIBUTING.md](../CONTRIBUTING.md#building)):

```sh
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release APK the build also makes (`app-release-unsigned.apk`) is unsigned and does not install. Debug builds add a
test voice in Diagnostics and a design gallery. The full on-device test plan for developers is
[`device-testing.md`](device-testing.md).

## Report a bug

Use the [bug report form](https://github.com/mauriciabad/cucuto/issues/new?template=bug_report.yml). Include your
Android build, LSPosed version and what Diagnostics shows, but never screenshot the Diagnostics decision list into
GitHub: it shows raw chat keys. Security problems go through [SECURITY.md](../SECURITY.md).
