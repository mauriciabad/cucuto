# WhatsApp call ringtone spike (#215)

- Status: static phase done, device run pending (#228). Decision: [ADR 0052](decisions/0052-whatsapp-call-ringtones.md).
- Date: 2026-10-24
- Device: the owner's phone, WhatsApp 2.26.42.12, WaEnhancer X 2.0.4 ([device notes](device.md))
- Code: [`spikes/whatsapp-ringtone/`](../spikes/whatsapp-ringtone/) (builds, has unit tests, not installed)

This report stays at the design level. It covers public Android framework APIs and what can be seen from outside
WhatsApp, nothing about WhatsApp's internals.

## 1. Summary

WhatsApp calls can ring with each contact's Cucuto ringtone through **one framework hook** inside WhatsApp's process:
swap the URI WhatsApp passes to `RingtoneManager.getRingtone(Context, Uri)` when the call is a 1:1 call from a
contact with a Cucuto ringtone. Everything else stays WhatsApp's: whether to ring at all, silent mode, Do Not
Disturb, vibration, looping and stopping. If anything is unclear, WhatsApp's own ringtone plays. The device run
(#228, needs a second phone) has to confirm the timing before the feature is built.

## 2. What we found

- **Public API.** WhatsApp plays its call ringtone through the public `RingtoneManager` and `Ringtone` APIs. By
  default it plays the system default ringtone (`content://settings/system/ringtone`), and loops by starting
  playback again.
- **Where the ring happens.** Calls are self-managed, so Telecom does not ring them, and the call notification's
  channel has no sound (M9 report §5). The ring happens only inside WhatsApp's process.
- **Permissions.** WhatsApp holds `READ_MEDIA_AUDIO` on this phone, so it can most likely open ring files in
  `Ringtones/cucuto/` through MediaStore (ADR 0039).
- **WaEnhancer X.** It does not hook the ringtone, notification or vibrator path, so the two modules should not clash.

## 3. Recommended approach

1. Cucuto's module gets an **optional** second target, `com.whatsapp`, next to `system` (ADR 0051).
2. While WhatsApp ringtones are off (the default), the WhatsApp side installs **no hook at all**.
3. While they're on, it hooks only `RingtoneManager.getRingtone(Context, Uri)`. When WhatsApp asks for a ringtone,
   the hook reads WhatsApp's own incoming-call notification (section 4). If that's a 1:1 call from a known contact
   with a ringtone, the hook swaps the URI for that contact's ring file. If the file can't be opened, it calls
   again with WhatsApp's URI.
4. The contact-to-ring-file map comes from libxposed Remote Preferences, which the module already reads
   (`RoutingContract`). It never comes from Cucuto's content provider, because a cold start would delay the ring
   (ADR 0018). Variants (fixed, rule-based, random per call) are resolved in the app and published there, the same
   way as for phone calls.
5. Every hook body catches everything. No files, no network, nothing is stored in WhatsApp's data, and no message
   notification is read.

## 4. Identifying the caller

No contact ID reaches the ringtone API: WhatsApp only passes a URI. The only caller detail available is the
**caller's display name** on WhatsApp's incoming-call notification (`Notification.CallStyle`, category `call`).
That notification carries no phone number, JID or shortcut ID.

Inside WhatsApp's process, the hook reads that notification with the public
`NotificationManager.getActiveNotifications()`, which returns only the calling app's own notifications. It skips
every notification except an incoming call and reads only three things: the caller's name, the post time and the
group-call flag. The name is matched against the names Cucuto already learned from the same contact's message
notifications in `system_server`.

ADR 0051 preferred identifying the caller in `system_server`. That gives the same name and nothing more, and it
would need a new channel from `system_server` into WhatsApp's process. Reading WhatsApp's own notification on the spot
is simpler, and it doesn't make the hook more intrusive.

## 5. Risks

| Risk | Mitigation |
| --- | --- |
| **Timing:** WhatsApp may load the ringtone before the call notification exists | Nothing found means no swap: WhatsApp's ringtone plays. The device run (#228) measures this. If it happens often, the design changes before it's built. |
| **Name collisions:** two contacts with the same display name | A name that maps to several contacts with different ringtones keeps WhatsApp's ringtone. The app explains why (#229). |
| Unsaved numbers, renamed contacts | No match, so WhatsApp's ringtone plays. |
| WhatsApp can't open the ring file | Calling again with WhatsApp's URI means it rings normally. |
| A WhatsApp update changes how it rings | The hook targets a framework method, not WhatsApp's code. If it's never called, nothing happens, and Diagnostics shows "no WhatsApp call seen yet". |
| A user taps LSPosed's "Use recommended" and ticks WhatsApp without opting in | With the feature off, no hook is installed. The app shows that the scope is active but unused, and how to remove it. |

**Alternatives we rejected:**

- **Writing WhatsApp's per-chat ringtone setting into its database.** That edits WhatsApp's private data, which is
  more intrusive than substituting a value at a framework call, and it breaks when WhatsApp changes its schema.
- **Ringing from `system_server` (option W-b, M9 report).** No WhatsApp scope is needed, but the user would have to
  silence WhatsApp's own ringtone by hand. If Cucuto's ring then fails or is skipped, the call is **silent**, which
  is worse than the wrong ringtone.

## 6. Opt-in scope

**How the scope is declared** (LSPosed/Vector and libxposed service, API 102). The module keeps
`staticScope=true` and lists both targets in `scope.list`: `system` and `com.whatsapp`. With `staticScope=true`,
the list is exhaustive: LSPosed refuses any other app, and a runtime `requestScope()` can only ask for a listed
entry. Listed entries are only *recommended*. They are never ticked on their own: the user ticks WhatsApp in
LSPosed, approves a `requestScope()` prompt, or taps "Use recommended" (see the risk table above). The app asks for
the scope only after the explainer screen, when the user turns the feature on. It checks the active scope through
the libxposed service and offers `removeScope()` when the feature is turned off. `ModuleMetadataTest` changes in the
implementation PR, not here.

**Explainer screen** (full screen, owner #166). Draft copy follows. "Ámbito" is the existing Spanish term for
"scope". The source link points at the production hook file once it exists.

| | English | Español |
| --- | --- | --- |
| Title | Ringtones for WhatsApp calls | Tonos para las llamadas de WhatsApp |
| Intro | To ring with each contact's voice on WhatsApp calls, Cucuto needs to run a small piece of code inside WhatsApp. This is optional: everything else in Cucuto works without it. | Para que las llamadas de WhatsApp suenen con la voz de cada contacto, Cucuto necesita ejecutar un pequeño fragmento de código dentro de WhatsApp. Es opcional: todo lo demás de Cucuto funciona sin esto. |
| What it does | When a call comes in, Cucuto swaps the ringtone file WhatsApp is about to play for that contact's ringtone. WhatsApp still decides whether to ring, vibrate or stay silent. | Cuando entra una llamada, Cucuto cambia el archivo de tono que WhatsApp va a reproducir por el tono de ese contacto. WhatsApp sigue decidiendo si suena, vibra o se queda en silencio. |
| What it reads | Only the caller's name on WhatsApp's incoming-call notification, to know whose ringtone to play. | Solo el nombre de quien llama en la notificación de llamada entrante de WhatsApp, para saber qué tono poner. |
| What it never reads | Messages, chats, photos and other media, call audio, or your contacts. It doesn't connect to the internet or save anything inside WhatsApp. | Mensajes, chats, fotos y otros archivos, el audio de las llamadas ni tus contactos. No se conecta a internet ni guarda nada dentro de WhatsApp. |
| Open source | Cucuto is open source. You, or your AI agent, can read the exact code that runs inside WhatsApp. | Cucuto es de código abierto. Tú, o tu agente de IA, podéis leer el código exacto que se ejecuta dentro de WhatsApp. |
| Link | Read the code on GitHub | Leer el código en GitHub |
| Steps | 1. Tap Continue. LSPosed will ask to add WhatsApp to Cucuto's scope. 2. Approve it. 3. Force stop WhatsApp, or restart your phone. | 1. Toca Continuar. LSPosed te pedirá añadir WhatsApp al ámbito de Cucuto. 2. Acéptalo. 3. Fuerza la detención de WhatsApp o reinicia el móvil. |
| How to undo | Turn off WhatsApp ringtones in Cucuto, then remove WhatsApp from Cucuto's scope in LSPosed. | Desactiva los tonos de WhatsApp en Cucuto y quita WhatsApp del ámbito de Cucuto en LSPosed. |
| Status: on | WhatsApp scope active | Ámbito de WhatsApp activo |
| Status: off | WhatsApp scope not active: WhatsApp calls keep their own ringtone | Ámbito de WhatsApp no activo: las llamadas de WhatsApp mantienen su tono |
| Buttons | Continue · Not now | Continuar · Ahora no |
| Fallback | Without the scope, you can still set a ringtone for a chat yourself in WhatsApp. Show me how | Sin el ámbito, puedes poner tú mismo un tono a un chat en WhatsApp. Enséñame cómo |
| Turned off | WhatsApp ringtones are off. You can remove WhatsApp from Cucuto's scope in LSPosed. | Los tonos de WhatsApp están desactivados. Puedes quitar WhatsApp del ámbito de Cucuto en LSPosed. |

## 7. WaEnhancer X

WaEnhancer X is open source. Its hooks don't touch the ringtone, notification or vibrator path, and Cucuto hooks
only a framework method there. Coexistence still has to be checked on the device (#228, step 6) before the feature
ships (ADR 0051 point 6).

## 8. Defaults (owner can override in #229)

- 1:1 calls only. Group calls keep WhatsApp's tones.
- `com.whatsapp` only. Business can be added later with its own device check.
- The caller is matched by display name, with the collision rule from section 5.

## 9. The spike module

`spikes/whatsapp-ringtone/` builds a throwaway debug APK (`app.mauri.cucuto.spike.waring`) that targets WhatsApp
only. It starts in mode `off`, where it installs nothing. Mode `observe` logs the `getRingtone`, `play` and `stop`
calls with timings. Mode `swap` swaps the ringtone for one test contact. It's configured over adb only. The logs
never contain names, numbers or full URIs. The swap rule (`SwapDecision`) has unit tests. Like the other spikes, it is
not built in CI:
`./gradlew :app:assembleDebug :app:testDebugUnitTest`.

## 10. Device test plan (#228, supervised)

This needs the owner present and their go-ahead to enable the spike in LSPosed. It also needs a **second phone** to
place WhatsApp calls to a test contact ("Cucuto Test"). It can share a session with the M9 run (#143).

1. **Install.** Install the spike. LSPosed shows WhatsApp as recommended but not ticked. Then request the scope at
   runtime (`--es scope request`), approve the prompt, and force stop WhatsApp.
2. **Observe.** Set mode `observe` and place a 1:1 call from the second phone. Pass: one `getRingtone` call, then
   repeated `play` calls while it rings, and `stop` on answer or decline. **Check whether the call notification is
   present at `getRingtone`.** Repeat 5 times, from a cold start and with WhatsApp already running.
3. **Swap.** Set mode `swap` with the test name and a file in `Ringtones/cucuto/`. Pass: the test contact rings
   with that file and loops. Vibration, answer, decline, silent mode and Do Not Disturb behave as before.
4. **No swap for others.** Calls from another contact, a group call and an unreadable URI all keep WhatsApp's
   ringtone.
5. **Remove.** `--es scope remove`, then call again: no hook, WhatsApp's ringtone.
6. **Coexistence.** Enable WaEnhancer X and the spike together and repeat steps 2 and 3. Pass:
   `adb logcat -b crash -d` is empty and WaEnhancer X's features still work.

**Recovery:** set mode `off`, untick the module in LSPosed, uninstall the spike, and force stop WhatsApp. No reboot
is needed.

**Results:** to be filled in after #228.
