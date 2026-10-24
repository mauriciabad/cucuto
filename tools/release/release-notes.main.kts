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
//   kotlin tools/release/release-notes.main.kts 0.2.0                 # before tagging: changelog + CHANGELOG.md
//   kotlin tools/release/release-notes.main.kts 0.2.0 --to v0.2.0 --notes dist/changes.md   # release.yml
//   ... --from v0.1.1        # previous tag (default: the newest v* tag before --to, other than v<version>)
//   ... --to REF             # last commit (default: tag v<version> if it exists, else HEAD)
//   ... --force              # rewrite the store changelog and the CHANGELOG.md section even if they exist
//   ... --check              # only check the store metadata limits (titles, descriptions, changelogs); no git
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

/** F-Droid's limits (https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/). */
val limits = mapOf("title.txt" to 50, "short_description.txt" to 80, "full_description.txt" to 4000)
val changelogLimit = 500

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

// --check: the limits every store client enforces, so a too-long text fails CI instead of being cut off.
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
        File(locale, "changelogs").listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (!Regex("""[1-9][0-9]*\.txt""").matches(file.name)) problems += "$file: name it <versionCode>.txt"
            val length = file.readText().trimEnd().length
            if (length > changelogLimit) problems += "$file has $length characters (at most $changelogLimit)"
        }
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

println("Cucuto $version (versionCode $versionCode): ${changes.size} user-facing changes in $range")
val problems = checkMetadata()
problems.forEach { System.err.println("warning: $it") }
