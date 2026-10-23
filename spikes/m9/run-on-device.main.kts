#!/usr/bin/env kotlin

/**
 * Cucuto M9 spike: supervised device session driver (issue #50, test plan in docs/m9-report.md §7).
 *
 *   kotlin run-on-device.main.kts <phase> [args] [--yes]
 *
 * Run with the owner present and the phone in hand. Phases that change the device refuse to run
 * without `--yes`; `preflight`, `verify`, `players`, `test-contact` and `collect` never change anything.
 * The script never enables the module, never changes an LSPosed scope and never reboots: those are
 * done by a human in the LSPosed manager (scope = System Framework only).
 *
 * Phases:
 *   preflight            read-only: device, LSPosed, Telecom classpath, ringtone-vibration support
 *   install --yes        build and install the spike APK (does not enable it)
 *   verify               read-only: did the module load and hook, is the system healthy
 *   swap-on --yes [uri]  ring with the spike tone and vibration (optional replacement URI)
 *   swap-off --yes       back to observe-only
 *   vibration-files --yes  write a test vibration XML to candidate paths (for the vibration_uri test)
 *   test-contact         read-only: find the contact named exactly "Cucuto Test" (created by the owner)
 *   set-ringtone --yes <uri>   set CUSTOM_RINGTONE on that one contact
 *   clear-ringtone --yes       remove it again
 *   players              read-only: active audio players (uid, usage, type); run DURING a ring
 *   collect              read-only: spike log lines to ../../private/m9/ (gitignored)
 *   recover [--yes]      print the recovery ladder; with --yes: swap off and uninstall the spike
 */

import java.io.File
import java.util.concurrent.TimeUnit

val argv: List<String> = args.toList()
val phase = argv.firstOrNull() ?: "help"
val confirmed = "--yes" in argv
val operands = argv.drop(1).filter { it != "--yes" }

val SPIKE_PKG = "app.mauri.cucuto.spike.m9"
val TAG = "cucuto-m9"
val LSPCTL = "/data/adb/modules/zygisk_lsposed/lspctl"
val TEST_CONTACT = "Cucuto Test"
val VIB_NAME = "cucuto-m9-vibration.xml"

val projectDir = File(System.getProperty("user.dir"))
val privateDir = File(projectDir, "../../private/m9").canonicalFile

fun sh(vararg cmd: String, quiet: Boolean = false): String {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor(180, TimeUnit.SECONDS)
    if (!quiet) print(out)
    return out.trim()
}

fun adb(vararg a: String, quiet: Boolean = false) = sh("adb", *a, quiet = quiet)
fun adbSu(cmd: String, quiet: Boolean = false) = adb("shell", "su -c \"$cmd\"", quiet = quiet)

fun header(s: String) = println("\n=== $s ===")
fun ok(s: String) = println("  [ok]   $s")
fun bad(s: String) = println("  [FAIL] $s")
fun note(s: String) = println("  .      $s")

fun requireConfirmation(what: String) {
    if (!confirmed) {
        println("\nThis phase changes the device: $what\nRe-run with --yes when the owner is watching the phone.")
        kotlin.system.exitProcess(2)
    }
}

fun requireDevice() {
    if (adb("devices", quiet = true).lines().none { it.endsWith("\tdevice") }) {
        bad("no adb device")
        kotlin.system.exitProcess(1)
    }
}

/** Lines this module wrote through libxposed's log (LSPosed's modules log, not logcat). */
fun moduleLog(): String = adbSu("grep -h -F '$SPIKE_PKG,$TAG,' /data/adb/lspd/log/modules_*.log", quiet = true)

/** Masks anything that looks like a phone number or a JID before it reaches the terminal. */
fun redact(s: String): String = s
    .replace(Regex("\\+?\\d[\\d ()-]{5,}\\d"), "<n>")
    .replace(Regex("[A-Za-z0-9._-]+@(s\\.whatsapp\\.net|lid|g\\.us)"), "<jid@$1>")

fun testContactId(): String? {
    val out = adb(
        "shell", "content", "query", "--uri", "content://com.android.contacts/contacts",
        "--projection", "_id", "--where", "\"display_name='$TEST_CONTACT'\"", quiet = true,
    )
    val ids = Regex("_id=(\\d+)").findAll(out).map { it.groupValues[1] }.toList()
    return if (ids.size == 1) ids.single() else null
}

fun vibrationXml(): String = """
    <vibration-effect>
      <waveform-effect>
        <waveform-entry durationMs="0" amplitude="0"/>
        <repeating>
          <waveform-entry durationMs="60" amplitude="255"/>
          <waveform-entry durationMs="80" amplitude="0"/>
          <waveform-entry durationMs="60" amplitude="255"/>
          <waveform-entry durationMs="80" amplitude="0"/>
          <waveform-entry durationMs="60" amplitude="255"/>
          <waveform-entry durationMs="1200" amplitude="0"/>
        </repeating>
      </waveform-effect>
    </vibration-effect>
""".trimIndent()

// A declaration, so the script runner does not print the value of the last expression.
val outcome: Any? = when (phase) {
    "preflight" -> {
        requireDevice()
        header("device")
        note("fingerprint: " + adb("shell", "getprop", "ro.build.fingerprint", quiet = true))
        note("sdk_full: " + adb("shell", "getprop", "ro.build.version.sdk_full", quiet = true))
        header("LSPosed")
        adbSu("$LSPCTL status")
        header("Telecom runs in system_server")
        val cp = adb("shell", "echo \$SYSTEMSERVERCLASSPATH", quiet = true)
        if ("service-telecom.jar" in cp) ok("service-telecom.jar is on SYSTEMSERVERCLASSPATH") else bad("service-telecom.jar not on SYSTEMSERVERCLASSPATH")
        val telecomProc = adb("shell", "ps -A -o NAME", quiet = true).lines().filter { "telecom" in it }
        if (telecomProc.isEmpty()) ok("no separate Telecom process") else bad("Telecom process found: $telecomProc")
        header("ringtone vibration (vibration_uri) support")
        note("config_ringtoneVibrationSettingsSupported = " + adb("shell", "cmd", "overlay", "lookup", "android", "android:bool/config_ringtoneVibrationSettingsSupported", quiet = true))
        note(adbSu("aflags list | grep -E 'enable_ringtone_haptics_customization|ringtone_vibration_utils_api'", quiet = true))
        header("spike installed?")
        note(adb("shell", "pm", "path", SPIKE_PKG, quiet = true).ifEmpty { "not installed" })
    }

    "install" -> {
        requireConfirmation("installs $SPIKE_PKG (inert until enabled in LSPosed)")
        requireDevice()
        sh("./gradlew", ":app:assembleDebug")
        adb("install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
        println("\nNext, by hand: LSPosed manager -> enable 'Cucuto M9 spike', scope = System Framework ONLY -> reboot -> verify.")
    }

    "verify" -> {
        requireDevice()
        header("health")
        note("boot_completed=" + adb("shell", "getprop", "sys.boot_completed", quiet = true))
        note("system_server pid=" + adb("shell", "pidof", "system_server", quiet = true))
        val crash = adb("logcat", "-b", "crash", "-d", quiet = true)
        if (crash.isBlank()) ok("crash buffer empty") else bad("crash buffer has entries (read them before continuing)")
        header("module log")
        val log = moduleLog()
        val expected = listOf(
            "telecom classes: Ringer=true Call=true RingtoneFactory=true",
            "hooked Ringer.startRinging",
            "hooked RingtoneFactory.getRingtone",
            "hooked Call.getRingtone",
            "hooked Ringer.vibrateIfNeeded",
            "hooked NotificationAttentionHelper.buzzBeepBlinkLocked",
        )
        for (e in expected) if (e in log) ok(e) else bad("missing: $e")
        note("swap property: '" + adb("shell", "getprop", "debug.cucuto.m9.swap", quiet = true) + "'")
    }

    "swap-on" -> {
        requireConfirmation("phone calls ring with the spike tone and vibration until swap-off or reboot")
        adb("shell", "setprop", "debug.cucuto.m9.uri", operands.firstOrNull() ?: "")
        adb("shell", "setprop", "debug.cucuto.m9.swap", "1")
        ok("swap on" + (operands.firstOrNull()?.let { " with $it" } ?: " with the spike provider tone"))
    }

    "swap-off" -> {
        requireConfirmation("returns the spike to observe-only")
        adb("shell", "setprop", "debug.cucuto.m9.swap", "0")
        adb("shell", "setprop", "debug.cucuto.m9.uri", "")
        ok("swap off")
    }

    "vibration-files" -> {
        requireConfirmation("writes $VIB_NAME to /data/local/tmp and /sdcard/Ringtones/cucuto-m9/")
        val local = File.createTempFile("cucuto-m9", ".xml").apply { writeText(vibrationXml()); deleteOnExit() }
        adb("push", local.path, "/data/local/tmp/$VIB_NAME")
        adb("shell", "chmod", "644", "/data/local/tmp/$VIB_NAME")
        adb("shell", "mkdir", "-p", "/sdcard/Ringtones/cucuto-m9")
        adb("push", local.path, "/sdcard/Ringtones/cucuto-m9/$VIB_NAME")
        println("\nCandidate vibration_uri values (try one per call; Telecom logs 'File exists:..., canRead:...' on failure):")
        println("  /product/media/vibration/ringtone/Heartbeat.xml   (control: a ROM file system_server can read)")
        println("  /data/local/tmp/$VIB_NAME")
        println("  /storage/emulated/0/Ringtones/cucuto-m9/$VIB_NAME")
    }

    "test-contact" -> {
        requireDevice()
        val id = testContactId()
        if (id == null) bad("need exactly one contact named '$TEST_CONTACT' (create it with the second phone's number)") else ok("test contact id=$id")
        id?.let {
            val out = adb("shell", "content", "query", "--uri", "content://com.android.contacts/contacts/$it", "--projection", "custom_ringtone", quiet = true)
            note(redact(out))
        }
    }

    "set-ringtone" -> {
        val uri = operands.firstOrNull() ?: run { bad("usage: set-ringtone --yes <uri>"); kotlin.system.exitProcess(2) }
        requireConfirmation("sets CUSTOM_RINGTONE of the '$TEST_CONTACT' contact to $uri")
        val id = testContactId() ?: run { bad("no unique '$TEST_CONTACT' contact"); kotlin.system.exitProcess(1) }
        adb("shell", "content", "update", "--uri", "content://com.android.contacts/contacts/$id", "--bind", "custom_ringtone:s:'$uri'")
        ok("set; now call the test contact's phone number from the second phone")
    }

    "clear-ringtone" -> {
        requireConfirmation("removes CUSTOM_RINGTONE from the '$TEST_CONTACT' contact")
        val id = testContactId() ?: run { bad("no unique '$TEST_CONTACT' contact"); kotlin.system.exitProcess(1) }
        adb("shell", "content", "update", "--uri", "content://com.android.contacts/contacts/$id", "--bind", "custom_ringtone:n:")
        ok("cleared")
    }

    "players" -> {
        requireDevice()
        header("active players (run while the phone is ringing)")
        val audio = adb("shell", "dumpsys", "audio", quiet = true)
        val section = audio.substringAfter("players:", "").substringBefore("\n\n")
        section.lines().filter { "piid" in it || "usage=" in it }
            .map { line -> Regex("(piid:\\d+|u/pid:\\d+/\\d+|state:\\w+|type:[\\w.]+|usage=\\w+|content=\\w+)").findAll(line).joinToString(" ") { it.value } }
            .filter { it.isNotBlank() }
            .forEach { note(it) }
        note("WhatsApp uid: " + adb("shell", "dumpsys package com.whatsapp | grep -m1 appId", quiet = true))
    }

    "collect" -> {
        requireDevice()
        privateDir.mkdirs()
        val out = File(privateDir, "m9-module-log-${System.currentTimeMillis()}.txt")
        out.writeText(redact(moduleLog()))
        ok("wrote ${out.path} (gitignored; never commit it or paste it unreviewed)")
    }

    "recover" -> {
        println(
            """
            Recovery ladder (same as docs/device-testing.md §0):
              1. Phone usable: adb uninstall $SPIKE_PKG, then reboot. LSPosed drops a module whose APK is gone.
              2. Boots but misbehaves, manager unreachable:
                   adb shell su -c 'touch /data/adb/modules/zygisk_lsposed/disable' && adb reboot
                 (disables all of LSPosed, WaEnhancer X too; delete the file and reboot to restore).
              3. No adb: KernelSU safe mode (hold Volume-Down through boot), then rung 1 or 2.
              4. Last resort: reflash the factory image for CP3A.261005.005.
            Also: kotlin run-on-device.main.kts clear-ringtone --yes   (if a test ringtone was set)
            """.trimIndent(),
        )
        if (confirmed) {
            adb("shell", "setprop", "debug.cucuto.m9.swap", "0")
            adb("uninstall", SPIKE_PKG)
            println("Uninstalled. Reboot when convenient so system_server unloads the hooks.")
        } else {
            println("Dry run: nothing changed. Add --yes to swap off and uninstall the spike.")
        }
    }

    else -> println(File(projectDir, "run-on-device.main.kts").readLines().drop(2).takeWhile { !it.startsWith(" */") }.joinToString("\n"))
}
