#!/usr/bin/env kotlin

// Creates Cucuto's release signing key and stores it as GitHub Actions secrets for .github/workflows/release.yml.
// Run by the owner, once, on their own machine. Guide: docs/releasing.md. Decision: docs/decisions/0021-signed-release-apk.md.
//
//   kotlin tools/release/setup-signing.main.kts                    # new key in ~/cucuto-release-key/, then upload
//   kotlin tools/release/setup-signing.main.kts --out DIR          # new key in DIR
//   kotlin tools/release/setup-signing.main.kts --existing FILE --alias NAME
//                                                                  # upload a keystore you already have (asks for
//                                                                  # its passwords)
//   ... --no-upload                                                # create or check the key, set no secrets
//   ... --repo OWNER/NAME                                          # default mauriciabad/cucuto
//
// Needs a JDK (keytool comes from the JVM running this script) and, to upload, the GitHub CLI logged in with access
// to the repository's secrets. Secret values go to `gh secret set` on stdin, never on a command line.

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import kotlin.system.exitProcess

val secretKeystore = "RELEASE_KEYSTORE_BASE64"
val secretKeystorePassword = "RELEASE_KEYSTORE_PASSWORD"
val secretKeyAlias = "RELEASE_KEY_ALIAS"
val secretKeyPassword = "RELEASE_KEY_PASSWORD"

fun fail(message: String): Nothing {
    System.err.println("error: $message")
    exitProcess(1)
}

// Arguments.
var outDir = File(System.getProperty("user.home"), "cucuto-release-key")
var existing: File? = null
var alias = "cucuto"
var repo = "mauriciabad/cucuto"
var upload = true
run {
    val queue = ArrayDeque(args.toList())
    fun value(flag: String) = queue.removeFirstOrNull() ?: fail("$flag needs a value")
    while (queue.isNotEmpty()) {
        when (val arg = queue.removeFirst()) {
            "--out" -> outDir = File(value(arg))
            "--existing" -> existing = File(value(arg))
            "--alias" -> alias = value(arg)
            "--repo" -> repo = value(arg)
            "--no-upload" -> upload = false
            "-h", "--help" -> {
                println("Usage: kotlin tools/release/setup-signing.main.kts [--out DIR | --existing FILE --alias NAME] [--repo OWNER/NAME] [--no-upload]")
                exitProcess(0)
            }
            else -> fail("unknown argument '$arg' (try --help)")
        }
    }
}

val keytool =
    File(System.getProperty("java.home"), "bin/keytool").takeIf(File::canExecute)?.path
        ?: fail("keytool not found next to the running JVM (${System.getProperty("java.home")}); run with a full JDK")

/** Runs [command] with [env] added, feeding [stdin]; returns stdout, or fails with stderr. */
fun run(
    command: List<String>,
    env: Map<String, String> = emptyMap(),
    stdin: String? = null,
): String {
    val process =
        ProcessBuilder(command)
            .apply { environment().putAll(env) }
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
    process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    if (process.waitFor() != 0) fail("${command.first()} ${command.getOrNull(1).orEmpty()} failed:\n$stderr$stdout")
    return stdout
}

/** 32 random bytes as URL-safe Base64 (43 characters): strong, and safe to paste anywhere. */
fun newPassword(): String =
    ByteArray(32).also(SecureRandom.getInstanceStrong()::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

fun askSecret(prompt: String): String {
    val console = System.console() ?: fail("no terminal to read the password from; run this script in a terminal")
    return String(console.readPassword(prompt) ?: fail("no password entered")).ifEmpty { fail("empty password") }
}

// The keystore and its passwords. The passwords live in the environment of keytool (`:env`), never in its arguments.
class Key(
    val file: File,
    val storePassword: String,
    val keyPassword: String,
    val created: Boolean,
)

fun existingKey(file: File): Key {
    if (!file.isFile) fail("$file does not exist")
    val storePassword = askSecret("Keystore password for ${file.name}: ")
    val keyPassword = askSecret("Password of key '$alias' (the same one again for a PKCS12 keystore): ")
    return Key(file, storePassword, keyPassword, created = false)
}

fun newKey(file: File): Key {
    if (file.exists()) {
        fail("$file already exists. Never replace a release key: reuse it with --existing $file --alias $alias")
    }
    file.parentFile.mkdirs()
    // PKCS12 keystores have one password for the store and its key (keytool ignores a separate -keypass).
    val password = newPassword()
    // RSA 4096, valid for 50 years: Android asks for at least 25 so every future update can use the same key.
    // The certificate is public (anyone can read it from the APK), so it carries only the app's name.
    run(
        listOf(
            keytool, "-genkeypair", "-storetype", "PKCS12", "-keystore", file.path, "-alias", alias,
            "-keyalg", "RSA", "-keysize", "4096", "-sigalg", "SHA256withRSA", "-validity", "18262",
            "-dname", "CN=Cucuto", "-storepass:env", "CUCUTO_STORE_PASSWORD", "-keypass:env", "CUCUTO_STORE_PASSWORD",
        ),
        env = mapOf("CUCUTO_STORE_PASSWORD" to password),
    )
    // Owner-only, read-only.
    file.setReadable(false, false)
    file.setReadable(true, true)
    file.setWritable(false, false)
    return Key(file, password, password, created = true)
}

val key = existing?.let { existingKey(it.absoluteFile) } ?: newKey(File(outDir, "cucuto-release.jks").absoluteFile)
val keystore = key.file
val storePassword = key.storePassword
val keyPassword = key.keyPassword
val created = key.created

// Opens the key with both passwords (catches a typo before it becomes a failing release) and reads its fingerprint.
val listing =
    run(
        listOf(keytool, "-list", "-v", "-keystore", keystore.path, "-alias", alias, "-storepass:env", "CUCUTO_STORE_PASSWORD"),
        env = mapOf("CUCUTO_STORE_PASSWORD" to storePassword),
    )
if (keyPassword != storePassword) {
    // keytool has no "check the key password" command; exporting through a throwaway copy uses it.
    val probe = File.createTempFile("cucuto-probe", ".p12").apply { delete() }
    try {
        run(
            listOf(
                keytool, "-importkeystore", "-srckeystore", keystore.path, "-srcalias", alias,
                "-srcstorepass:env", "S", "-srckeypass:env", "K", "-destkeystore", probe.path,
                "-deststoretype", "PKCS12", "-deststorepass:env", "S", "-destkeypass:env", "S", "-noprompt",
            ),
            env = mapOf("S" to storePassword, "K" to keyPassword),
        )
    } finally {
        probe.delete()
    }
}
val fingerprint =
    Regex("""SHA256:\s*([0-9A-F:]+)""").find(listing)?.groupValues?.get(1)
        ?: fail("could not read the certificate's SHA-256 fingerprint from keytool")

println()
println("Release key: $keystore (alias '$alias')")
println("Certificate SHA-256: $fingerprint")
if (created) {
    println()
    println("Keystore password (also the key password): $storePassword")
    println()
    println("BACK IT UP NOW. Every Cucuto update must be signed with this exact key; it cannot be recreated.")
    println("If it is lost, users can only move to a new key by uninstalling Cucuto (losing its data unless they")
    println("restore a backup) and re-enabling the module in LSPosed. If it leaks, anyone can publish an update that")
    println("phones accept as Cucuto, running inside system_server through the module.")
    println("  1. Save the password in your password manager.")
    println("  2. Copy $keystore to two places you control (for example the password manager as an")
    println("     attachment and an offline drive). Never commit it; *.jks is in .gitignore.")
    println()
    print("Type 'saved' once the password and the keystore are backed up: ")
    if (readlnOrNull()?.trim() != "saved") fail("not confirmed; the key stays in $outDir, run again with --existing $keystore --alias $alias")
}

if (!upload) {
    println("Skipping the upload (--no-upload).")
    exitProcess(0)
}

val gh =
    listOf("/opt/homebrew/bin/gh", "/usr/local/bin/gh", "/usr/bin/gh").firstOrNull { File(it).canExecute() } ?: "gh"
val secrets =
    linkedMapOf(
        secretKeystore to Base64.getEncoder().encodeToString(keystore.readBytes()),
        secretKeystorePassword to storePassword,
        secretKeyAlias to alias,
        secretKeyPassword to keyPassword,
    )
for ((name, value) in secrets) {
    run(listOf(gh, "secret", "set", name, "--repo", repo, "--app", "actions"), stdin = value)
    println("Set $name on $repo")
}
// Not a secret: the certificate is public. release.yml refuses to publish an APK signed by any other certificate.
val certPin = fingerprint.replace(":", "").lowercase()
run(listOf(gh, "variable", "set", "RELEASE_CERT_SHA256", "--repo", repo, "--body", certPin))
println("Set variable RELEASE_CERT_SHA256=$certPin on $repo")
println()
println("Done. Push a tag like v0.1.0 (docs/releasing.md); release.yml builds, signs and publishes the APK.")
println("Release notes list the certificate fingerprint; it must stay $fingerprint.")
