#!/usr/bin/env kotlin

/**
 * cucuto M0 — supervised device session driver.
 *
 *   kotlin run-on-device.main.kts <phase> [--yes]
 *
 * Run this with the phone in your hand. Phases that change the device state refuse to run without
 * `--yes`, and `preflight`, `verify`, `status` and `collect` never change anything.
 *
 * The one thing this script deliberately does NOT do is enable the module. Enabling has to happen
 * in the LSPosed manager so a human picks the scope, and the scope must be **System Framework
 * (android)** and nothing else. See `recover` before the first reboot.
 *
 * Phases:
 *   preflight  read-only: device identity, LSPosed status, WhatsApp/WaEnhancer health
 *   install    build and install the spike APK (does not enable it)
 *   verify     read-only: did the module load in system_server, is the system healthy
 *   matrix     drive the #11/#12 test matrix over adb (posts only into the spike's own channel)
 *   collect    read-only: pull logs into private/ (gitignored)
 *   recover    print the recovery ladder; with --yes, run the safe first rung
 *   uninstall  remove the spike app from the device
 */

import java.io.File
import java.util.concurrent.TimeUnit

val args0: Array<String> = args
val phase = args0.firstOrNull() ?: "help"
val confirmed = args0.contains("--yes")

val SPIKE_PKG = "app.mauri.cucuto.spike"
val WHATSAPP = "com.whatsapp"
val WAENHANCER = "com.waenhancer"
val LSPCTL = "/data/adb/modules/zygisk_lsposed/lspctl"
val TAG = "cucuto-m0"

val projectDir = File(System.getProperty("user.dir"))
val privateDir = File(projectDir, "../../private").canonicalFile

fun sh(vararg cmd: String, quiet: Boolean = false): String {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor(120, TimeUnit.SECONDS)
    if (!quiet) print(out)
    return out.trim()
}

fun adb(vararg a: String, quiet: Boolean = false) = sh("adb", *a, quiet = quiet)
fun adbSu(cmd: String, quiet: Boolean = false) = adb("shell", "su", "-c", cmd, quiet = quiet)

fun header(s: String) = println("\n=== $s ===")
fun ok(s: String) = println("  [ok]   $s")
fun bad(s: String) = println("  [FAIL] $s")
fun note(s: String) = println("  .      $s")

fun requireConfirmation(what: String) {
    if (!confirmed) {
        println("\nThis phase changes the device: $what")
        println("Re-run with --yes when you are ready and watching the phone.")
        kotlin.system.exitProcess(2)
    }
}

fun deviceOnline(): Boolean = adb("devices", quiet = true).lines().any { it.endsWith("\tdevice") }

// --------------------------------------------------------------------- phases

fun preflight() {
    header("device")
    if (!deviceOnline()) {
        bad("no adb device. Plug the phone in and authorise the host.")
        kotlin.system.exitProcess(1)
    }
    ok("adb device present")
    note("fingerprint: " + adb("shell", "getprop", "ro.build.fingerprint", quiet = true))
    note("sdk: " + adb("shell", "getprop", "ro.build.version.sdk_full", quiet = true))

    header("root + framework")
    val id = adbSu("id -u", quiet = true)
    if (id.contains("0")) ok("su works (uid 0)") else bad("su did not return uid 0: $id")
    val status = adbSu("$LSPCTL --json status", quiet = true)
    println("  $status")
    if (status.contains("\"apiVersion\":102")) ok("libxposed API 102") else bad("unexpected API level")
    if (status.contains("\"systemServerRequested\":true")) ok("system_server hooked by LSPosed")

    header("apps that must keep working")
    for (pkg in listOf(WHATSAPP, WAENHANCER)) {
        val v = adb("shell", "dumpsys", "package", pkg, quiet = true)
            .lines().firstOrNull { it.contains("versionName") }?.trim() ?: "<not installed>"
        note("$pkg $v")
    }

    header("spike app")
    val installed = adb("shell", "pm", "list", "packages", SPIKE_PKG, quiet = true).contains(SPIKE_PKG)
    if (installed) ok("$SPIKE_PKG installed") else note("$SPIKE_PKG not installed yet (run: install --yes)")

    header("recovery (read this before enabling anything)")
    printRecoveryLadder()
}

fun install() {
    requireConfirmation("installs the spike APK (it stays disabled in LSPosed until you enable it)")
    header("build")
    sh("./gradlew", ":app:assembleDebug")
    val apk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
    if (!apk.isFile) {
        bad("APK not found at ${apk.path}")
        kotlin.system.exitProcess(1)
    }
    header("install")
    adb("install", "-r", apk.path)
    println(
        """
        |
        |Next, by hand, in the LSPosed manager:
        |  1. Modules -> "cucuto M0 spike" -> enable.
        |  2. Scope: tick **System Framework** only. Do NOT tick WhatsApp.
        |     (module.prop sets staticScope=true with scope.list = android, so the manager
        |      should not even offer WhatsApp. If it does, stop and report it.)
        |  3. Reboot once. Then: kotlin run-on-device.main.kts verify
        """.trimMargin(),
    )
}

fun verify() {
    header("boot + system health")
    val booted = adb("shell", "getprop", "sys.boot_completed", quiet = true)
    if (booted == "1") ok("sys.boot_completed=1") else bad("sys.boot_completed=$booted")

    val ssPid = adb("shell", "pidof", "system_server", quiet = true)
    if (ssPid.isNotBlank()) ok("system_server alive (pid $ssPid)") else bad("system_server NOT running")

    header("module loaded?")
    val log = adb("logcat", "-d", "-s", TAG, quiet = true)
    if (log.contains("system_server starting")) ok("module ran onSystemServerStarting")
    else bad("no onSystemServerStarting line — module not loaded or not scoped to System Framework")
    log.lines().filter { it.contains("hooked ") }.forEach { ok(it.substringAfter(TAG).trim()) }
    log.lines().filter { it.contains("no method") || it.contains("not found") || it.contains("swallowed") }
        .forEach { bad(it.substringAfter(TAG).trim()) }

    header("WhatsApp + WaEnhancer still healthy")
    for (pkg in listOf(WHATSAPP, WAENHANCER)) {
        val pid = adb("shell", "pidof", pkg, quiet = true)
        if (pid.isNotBlank()) ok("$pkg running (pid $pid)") else note("$pkg not running (open it, then re-check)")
    }
    val crashes = adb("logcat", "-d", "-b", "crash", quiet = true)
        .lines().filter { it.contains(WHATSAPP) || it.contains(WAENHANCER) || it.contains("system_server") }
    if (crashes.isEmpty()) ok("no crash-buffer entries for WhatsApp / WaEnhancer / system_server")
    else crashes.take(10).forEach { bad(it) }
}

fun matrix() {
    requireConfirmation("posts test notifications and changes DND / ringer mode (both restored at the end)")

    fun post(what: String) {
        adb(
            "shell", "am", "broadcast",
            "-a", "$SPIKE_PKG.POST",
            "-n", "$SPIKE_PKG/.debugapp.SpikeReceiver",
            "--es", "what", what,
            quiet = true,
        )
        Thread.sleep(2500)
    }

    val originalRinger = adb("shell", "settings", "get", "global", "mode_ringer", quiet = true)
    header("baseline: ringer NORMAL, DND off")
    adb("shell", "cmd", "notification", "set_dnd", "off", quiet = true)
    adb("shell", "cmd", "audio", "set-ringer-mode", "NORMAL", quiet = true)

    adb("logcat", "-c", quiet = true)

    for (what in listOf("one_to_one", "one_to_one_lid", "group", "group_summary_alert", "alert_once", "burst")) {
        header("post: $what")
        post(what)
        adb("logcat", "-d", "-s", TAG, quiet = true)
            .lines().filter { it.contains("ALERTING") || it.contains("PLAY_") || it.contains("CLIP_OPEN") }
            .forEach { println("  $it") }
        adb("logcat", "-c", quiet = true)
    }

    header("vibrator: did our effect actually play?")
    println(adb("shell", "dumpsys", "vibrator_manager", quiet = true).lines().take(40).joinToString("\n"))

    header("DND = priority")
    adb("shell", "cmd", "notification", "set_dnd", "priority", quiet = true)
    post("one_to_one")
    println(adb("logcat", "-d", "-s", TAG, quiet = true))

    header("ringer = SILENT")
    adb("shell", "cmd", "notification", "set_dnd", "off", quiet = true)
    adb("shell", "cmd", "audio", "set-ringer-mode", "SILENT", quiet = true)
    adb("logcat", "-c", quiet = true)
    post("one_to_one")
    println(adb("logcat", "-d", "-s", TAG, quiet = true))

    header("restore")
    adb("shell", "cmd", "notification", "set_dnd", "off", quiet = true)
    adb("shell", "cmd", "audio", "set-ringer-mode", "NORMAL", quiet = true)
    post("cancel")
    ok("DND off, ringer NORMAL, test notifications cancelled (was: mode_ringer=$originalRinger)")
}

fun collect() {
    privateDir.mkdirs()
    val stamp = System.currentTimeMillis()
    val out = File(privateDir, "m0-session-$stamp.log")
    out.writeText(adb("logcat", "-d", quiet = true))
    ok("full logcat -> ${out.path}  (private/ is gitignored; never commit or paste it)")
    val tagged = File(privateDir, "m0-tagged-$stamp.log")
    tagged.writeText(adb("logcat", "-d", "-s", TAG, quiet = true))
    ok("cucuto-m0 lines only -> ${tagged.path}  (already redacted by the module, still private)")
}

fun printRecoveryLadder() {
    println(
        """
        |  Rung 1 — disable the module, no reboot needed to prepare:
        |      adb uninstall $SPIKE_PKG
        |    LSPosed drops a module whose APK is gone. This is the cleanest fix and the one to
        |    reach for first if the phone is still usable.
        |
        |  Rung 2 — phone boots but is misbehaving, LSPosed manager unreachable:
        |      adb shell su -c 'touch /data/adb/modules/zygisk_lsposed/disable'
        |      adb reboot
        |    Disables the whole LSPosed framework (WaEnhancer too) on the next boot. Remove the
        |    file and reboot again to restore.
        |
        |  Rung 3 — phone will not boot far enough for adb:
        |    KernelSU safe mode. Power off, then hold Volume-Down through the boot. KernelSU
        |    disables every module (LSPosed included) for that boot. Then apply rung 1 or 2.
        |
        |  Rung 4 — last resort:
        |      fastboot and reflash the matching factory image for CP3A.261005.005.
        |
        |  Health check after any reboot:
        |      adb shell getprop sys.boot_completed     # expect 1
        |      adb shell pidof system_server            # expect a pid
        |      adb shell su -c '$LSPCTL status'
        """.trimMargin(),
    )
}

fun recover() {
    header("recovery ladder")
    printRecoveryLadder()
    if (confirmed) {
        header("running rung 1")
        adb("uninstall", SPIKE_PKG)
        ok("spike app removed; LSPosed will drop the module")
    } else {
        note("re-run with --yes to execute rung 1 (uninstall the spike app)")
    }
}

fun uninstall() {
    requireConfirmation("removes $SPIKE_PKG from the device")
    adb("uninstall", SPIKE_PKG)
    ok("uninstalled")
}

// --------------------------------------------------------------------- main

when (phase) {
    "preflight" -> preflight()
    "install" -> install()
    "verify" -> verify()
    "matrix" -> matrix()
    "collect" -> collect()
    "recover" -> recover()
    "uninstall" -> uninstall()
    else -> println(
        """
        |cucuto M0 — supervised device session driver
        |
        |  kotlin run-on-device.main.kts <phase> [--yes]
        |
        |  preflight   read-only  device + LSPosed + app health, and the recovery ladder
        |  install     --yes      build and install the spike APK (enable by hand afterwards)
        |  verify      read-only  did the module load, is system_server healthy
        |  matrix      --yes      drive the #11/#12 test matrix over adb
        |  collect     read-only  pull logs into private/
        |  recover     --yes      print the recovery ladder; with --yes run rung 1
        |  uninstall   --yes      remove the spike app
        |
        |Order for a first session:
        |  preflight -> install --yes -> (enable in LSPosed, scope = System Framework only)
        |  -> reboot -> verify -> matrix --yes -> collect -> recover --yes
        """.trimMargin(),
    )
}
