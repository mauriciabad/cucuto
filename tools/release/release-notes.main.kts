#!/usr/bin/env kotlin

// Release notes for Cucuto from the Conventional Commits since the previous version tag (ADR 0046). One run writes
// all three, so they never disagree:
//
//   - the store changelog fastlane/metadata/android/en-US/changelogs/<versionCode>.txt (F-Droid, IzzyOnDroid and
//     Obtainium show it; plain text, at most 500 characters),
//   - a section in CHANGELOG.md,
//   - with --notes FILE, the Markdown body of the GitHub release (release.yml adds the banner, checksums and
//     certificate around it).
//
// It also sets versionCode and versionName in android/app/build.gradle.kts, so the release PR carries the version
// F-Droid's update check reads from the tag.
//
//   kotlin tools/release/release-notes.main.kts 0.2.0                 # before tagging: changelog + CHANGELOG.md
//   kotlin tools/release/release-notes.main.kts 0.2.0 --to v0.2.0 --notes dist/changes.md   # release.yml
//   ... --from v0.1.1        # previous tag (default: the newest v* tag before --to, other than v<version>)
//   ... --to REF             # last commit (default: tag v<version> if it exists, else HEAD)
//   ... --force              # rewrite the store changelog and the CHANGELOG.md section even if they exist
//   ... --check              # only check the store metadata (lengths, tags, brand rules, Play images); no git
//
// Listed: feat (New), fix and perf (Fixes), docs (Docs), minus internal scopes (see internalScopes). A line
// "Release note: <words for users>" in a commit body (for squash merges, the PR description) replaces its subject;
// "Release note: skip" leaves it out.
//
// Files that already exist are kept (hand edits win) unless --force. Run from the repository root. Spanish
// (es-ES) changelogs are written by hand when there is time; without one, store clients show the English text.

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

val repoUrl = "https://github.com/mauriciabad/cucuto"
val metadataDir = File("fastlane/metadata/android")
val changelogFile = File("CHANGELOG.md")

/**
 * The strictest limit of the stores that read this metadata: F-Droid
 * (https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/) allows a 50-character title, Google
 * Play 30 (ADR 0071). Both allow 80 for the short description, 4000 for the full one and 500 for a changelog.
 */
val limits = mapOf("title.txt" to 30, "short_description.txt" to 80, "full_description.txt" to 4000)
val changelogLimit = 500

/**
 * Google Play renders only bold, italics and line breaks in a description, F-Droid turns line breaks into `<br>`:
 * plain text with these tags reads the same in both (ADR 0071).
 */
val allowedTags = setOf("<b>", "</b>", "<i>", "</i>")

/** Each full description ends with this disclaimer (Meta's brand guidelines, Play's IP policy; ADR 0046). */
val disclaimers = mapOf("en-US" to "Not affiliated with WhatsApp or Meta.", "es-ES" to "Sin relación con WhatsApp ni Meta.")

/** Other brands that must stay out of the title and the opening words of the short description (ADR 0046). */
val brands = listOf("WhatsApp", "Meta")

/** True when [text] has [brand] as a whole word, in any case. */
fun names(text: String, brand: String) = Regex("""\b$brand\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

fun fail(message: String): Nothing {
    System.err.println("error: $message")
    exitProcess(1)
}

// Arguments.
var version: String? = null
var from: String? = null
var to: String? = null
var notesFile: File? = null
var force = false
var checkOnly = false
run {
    val queue = ArrayDeque(args.toList())
    fun value(flag: String) = queue.removeFirstOrNull() ?: fail("$flag needs a value")
    while (queue.isNotEmpty()) {
        when (val arg = queue.removeFirst()) {
            "--from" -> from = value(arg)
            "--to" -> to = value(arg)
            "--notes" -> notesFile = File(value(arg))
            "--force" -> force = true
            "--check" -> checkOnly = true
            "-h", "--help" -> {
                println("usage: kotlin tools/release/release-notes.main.kts VERSION [--from TAG] [--to REF] [--notes FILE] [--force]")
                println("       kotlin tools/release/release-notes.main.kts --check")
                exitProcess(0)
            }
            else -> if (arg.startsWith("-") || version != null) fail("unexpected argument '$arg'") else version = arg
        }
    }
}

/** Width, height and whether a PNG has an alpha channel (color types 4 and 6, or a tRNS chunk), or null. */
data class PngInfo(val width: Int, val height: Int, val alpha: Boolean)

fun pngInfo(file: File): PngInfo? {
    val bytes = file.readBytes()
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return null
    fun int(at: Int) = (0..3).fold(0) { acc, i -> (acc shl 8) or (bytes[at + i].toInt() and 0xFF) }
    val colorType = bytes[25].toInt()
    // tRNS gives a palette or an RGB image transparency; it sits between IHDR and IDAT, so the first chunks are enough.
    val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
    return PngInfo(int(16), int(20), alpha = colorType == 4 || colorType == 6 || "tRNS" in head.substringBefore("IDAT"))
}

// Google Play's preview assets (https://support.google.com/googleplay/android-developer/answer/9866151, ADR 0071).
fun checkImages(locale: File, problems: MutableList<String>) {
    val images = File(locale, "images")
    File(images, "icon.png").takeIf { it.isFile }?.let { icon ->
        val info = pngInfo(icon)
        if (info == null || info.width != 512 || info.height != 512 || !info.alpha) {
            problems += "$icon: Play wants a 512 × 512 32-bit PNG (with alpha)"
        }
    }
    File(images, "featureGraphic.png").takeIf { it.isFile }?.let { graphic ->
        val info = pngInfo(graphic)
        if (info == null || info.width != 1024 || info.height != 500 || info.alpha) {
            problems += "$graphic: Play wants a 1024 × 500 24-bit PNG (no alpha)"
        }
    }
    val screenshots = File(images, "phoneScreenshots").listFiles()?.sortedBy { it.name }.orEmpty()
    if (screenshots.size > 8) problems += "$images/phoneScreenshots: ${screenshots.size} screenshots (Play takes at most 8)"
    for (shot in screenshots) {
        val info = pngInfo(shot)
        if (info == null) {
            problems += "$shot: not a PNG"
            continue
        }
        val (short, long) = minOf(info.width, info.height) to maxOf(info.width, info.height)
        if (info.alpha) problems += "$shot: has an alpha channel (Play refuses it; export with ./gradlew :app:exportStoreScreenshots)"
        if (short < 320 || long > 3840) problems += "$shot: ${info.width} × ${info.height} (Play: every side 320 to 3840 px)"
        if (long > 2 * short) problems += "$shot: ${info.width} × ${info.height} (Play: the long side at most twice the short one)"
    }
}

// --check: the limits every store enforces, so a too-long text or a refused image fails CI instead of being cut off
// or rejected at upload.
fun checkMetadata(): List<String> {
    val problems = mutableListOf<String>()
    val locales = metadataDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
    if (locales.isEmpty()) problems += "no locales in $metadataDir"
    for (locale in locales) {
        for ((name, max) in limits) {
            val file = File(locale, name)
            if (!file.isFile) {
                problems += "$file is missing"
                continue
            }
            val length = file.readText().trimEnd().length
            if (length == 0) problems += "$file is empty"
            if (length > max) problems += "$file has $length characters (at most $max)"
        }
        val title = File(locale, "title.txt").takeIf { it.isFile }?.readText().orEmpty()
        brands.filter { names(title, it) }.forEach { problems += "$locale/title.txt names $it" }
        val opening = File(locale, "short_description.txt").takeIf { it.isFile }?.readText().orEmpty()
            .split(Regex("""\s+""")).filter { it.isNotEmpty() }.take(3).joinToString(" ")
        brands.filter { names(opening, it) }
            .forEach { problems += "$locale/short_description.txt opens with $it (keep it out of the first words)" }
        File(locale, "full_description.txt").takeIf { it.isFile }?.readText()?.let { description ->
            Regex("""<[^>]*>""").findAll(description).map { it.value }.filter { it !in allowedTags }.distinct()
                .forEach { problems += "$locale/full_description.txt uses $it (Play renders only ${allowedTags.joinToString(" ")})" }
            val disclaimer = disclaimers[locale.name]
            if (disclaimer == null) {
                problems += "$locale: add its WhatsApp and Meta disclaimer to `disclaimers` in this script"
            } else if (!description.trimEnd().endsWith(disclaimer)) {
                problems += "$locale/full_description.txt must end with \"$disclaimer\""
            }
        }
        File(locale, "changelogs").listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (!Regex("""[1-9][0-9]*\.txt""").matches(file.name)) problems += "$file: name it <versionCode>.txt"
            val length = file.readText().trimEnd().length
            if (length > changelogLimit) problems += "$file has $length characters (at most $changelogLimit)"
        }
        checkImages(locale, problems)
    }
    return problems
}

if (checkOnly) {
    val problems = checkMetadata()
    problems.forEach { System.err.println("error: $it") }
    if (problems.isNotEmpty()) exitProcess(1)
    println("Store metadata OK")
    exitProcess(0)
}

val match =
    Regex("""(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})""").matchEntire(version ?: fail("VERSION is required, e.g. 0.2.0"))
        ?: fail("VERSION must look like 1.2.3 (each part 0-999, no leading zeros), got '$version'")
val (major, minor, patch) = match.destructured
val versionCode = major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
val tag = "v$version"

fun git(vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git") + arguments).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) fail("git ${arguments.joinToString(" ")} failed")
    return output
}

fun tagExists(name: String) =
    ProcessBuilder("git", "rev-parse", "-q", "--verify", "refs/tags/$name").start().waitFor() == 0

val end = to ?: if (tagExists(tag)) tag else "HEAD"
val start =
    from ?: git("tag", "--list", "v*", "--merged", end, "--sort=-v:refname")
        .lines()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && it != tag && Regex("""v\d+\.\d+\.\d+""").matches(it) }
val range = if (start != null) "$start..$end" else end
val releaseDate = Instant.ofEpochSecond(git("log", "-1", "--format=%ct", end).trim().toLong()).atZone(ZoneOffset.UTC).toLocalDate()

// Conventional Commits: type(scope)!: description (#PR), as squash merges write them.
data class Change(val group: Group, val text: String, val pr: String?, val breaking: Boolean)

enum class Group(val title: String) { New("New"), Fixes("Fixes"), Docs("Docs") }

val groups = mapOf("feat" to Group.New, "fix" to Group.Fixes, "perf" to Group.Fixes, "docs" to Group.Docs)

/**
 * Scopes that never reach the APK or its users: the website (it has its own deploys), CI, dependency bumps, the
 * owner log, release chores and test infrastructure. Everything else in feat, fix, perf and docs is listed.
 */
val internalScopes = setOf("web", "ci", "deps", "deps-dev", "owner-log", "release", "screenshots", "spike", "test")

val subjectPattern = Regex("""^(\w+)(?:\(([^)]+)\))?(!)?:\s*(.+?)(?:\s+\(#(\d+)\))?$""")

val changes =
    git("log", "--no-merges", "--format=%s%x1f%b%x1e", range)
        .split('\u001e')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val subject = entry.substringBefore('\u001f').trim()
            val body = entry.substringAfter('\u001f', "")
            val m = subjectPattern.matchEntire(subject) ?: return@mapNotNull null
            val (type, scope, bang, description, pr) = m.destructured
            val group = groups[type.lowercase()] ?: return@mapNotNull null
            if (scope.split(',').map { it.trim().lowercase() }.any { it in internalScopes }) return@mapNotNull null
            val breaking = bang.isNotEmpty() || Regex("""(?m)^BREAKING[ -]CHANGE:""").containsMatchIn(body)
            // A "Release note: …" line in the commit body (the PR description, for squash merges) replaces the
            // subject with words for users; "Release note: skip" leaves the commit out.
            val note = Regex("""(?mi)^Release[ -]note:\s*(.+)$""").find(body)?.groupValues?.get(1)?.trim()
            if (note.equals("skip", ignoreCase = true)) return@mapNotNull null
            val text = (note ?: description).replaceFirstChar { it.uppercaseChar() }.trimEnd('.')
            Change(group, text, pr.ifEmpty { null }, breaking)
        }.reversed() // oldest first, as they landed

val byGroup = Group.entries.associateWith { g -> changes.filter { it.group == g } }.filterValues { it.isNotEmpty() }

fun Change.markdown(): String = buildString {
    append("- ")
    if (breaking) append("**Breaking:** ")
    append(text)
    if (pr != null) append(" (#$pr)")
}

val compareUrl = if (start != null) "$repoUrl/compare/$start...$tag" else "$repoUrl/commits/$tag"
val noChanges = "Maintenance only: nothing you will notice."

// 1. Release body (Markdown).
notesFile?.let { file ->
    val text = buildString {
        if (byGroup.isEmpty()) appendLine(noChanges).appendLine()
        for ((group, items) in byGroup) {
            appendLine("## ${group.title}").appendLine()
            items.forEach { appendLine(it.markdown()) }
            appendLine()
        }
        appendLine("**Full changelog:** $compareUrl")
    }
    file.absoluteFile.parentFile.mkdirs()
    file.writeText(text)
    println("Wrote $file")
}

// 2. Store changelog (plain text, <= 500 characters): New and Fixes only; the link covers the rest.
val storeChangelog = File(metadataDir, "en-US/changelogs/$versionCode.txt")
if (force || !storeChangelog.exists()) {
    val more = "More: $repoUrl/releases/tag/$tag"
    val lines = mutableListOf<String>()
    val storeGroups = byGroup.filterKeys { it != Group.Docs }
    if (storeGroups.isEmpty()) lines += noChanges
    for ((group, items) in storeGroups) {
        lines += "${group.title}:"
        items.forEach { lines += "• " + (if (it.breaking) "Breaking: " else "") + it.text }
    }
    // Keep whole lines that fit with the link; drop a trailing group title left without items.
    val kept = mutableListOf<String>()
    for (line in lines) {
        if ((kept + line + more).joinToString("\n").length > changelogLimit) break
        kept += line
    }
    while (kept.isNotEmpty() && kept.last().endsWith(":")) kept.removeAt(kept.lastIndex)
    storeChangelog.parentFile.mkdirs()
    storeChangelog.writeText((kept + more).joinToString("\n") + "\n")
    println("Wrote $storeChangelog")
} else {
    println("Kept $storeChangelog (exists; --force rewrites it)")
}

// 3. CHANGELOG.md: a section per version, newest first, under the "## [Unreleased]" heading if there is one.
val heading = "## [$version]"
val existing = if (changelogFile.exists()) changelogFile.readText() else ""
if (force || heading !in existing) {
    val section = buildString {
        appendLine("$heading - $releaseDate").appendLine()
        if (byGroup.isEmpty()) appendLine(noChanges).appendLine()
        for ((group, items) in byGroup) {
            appendLine("### ${group.title}").appendLine()
            items.forEach { appendLine(it.markdown().replace(Regex("""\(#(\d+)\)$"""), "([#$1]($repoUrl/pull/$1))")) }
            appendLine()
        }
        appendLine("[Release]($repoUrl/releases/tag/$tag) · [All changes]($compareUrl)").appendLine()
    }
    var text = existing.ifEmpty { "# Changelog\n\n" }
    if (force && heading in text) {
        // Replace the old section, up to the next version heading.
        text = text.replace(Regex("""(?s)${Regex.escape(heading)} .*?(?=\n## \[|\z)"""), section.trimEnd() + "\n")
    } else {
        val anchor = Regex("""(?m)^## \[(?!Unreleased\])""").find(text)
        text = if (anchor != null) text.substring(0, anchor.range.first) + section + text.substring(anchor.range.first) else text.trimEnd() + "\n\n" + section
    }
    changelogFile.writeText(text.trimEnd() + "\n")
    println("Wrote $changelogFile ($heading)")
} else {
    println("Kept $changelogFile ($heading exists; --force rewrites it)")
}

// 4. The version in android/app/build.gradle.kts, so the tagged source names its own version: F-Droid's update
// check reads versionCode and versionName from it (fdroid/README.md). Releases still pass -Pcucuto.version.
val appBuild = File("android/app/build.gradle.kts")
if (appBuild.isFile) {
    val before = appBuild.readText()
    val after = before
        .replace(Regex("""(?m)^(\s*versionCode = )\d+$"""), "$1$versionCode")
        .replace(Regex("""(?m)^(\s*versionName = )"[^"]*"$"""), "$1\"$version\"")
    if (after != before) {
        appBuild.writeText(after)
        println("Wrote $appBuild (versionCode $versionCode, versionName $version)")
    }
}

println("Cucuto $version (versionCode $versionCode): ${changes.size} user-facing changes in $range")
val problems = checkMetadata()
problems.forEach { System.err.println("warning: $it") }
