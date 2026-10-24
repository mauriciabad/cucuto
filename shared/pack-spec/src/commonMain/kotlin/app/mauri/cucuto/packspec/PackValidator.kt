package app.mauri.cucuto.packspec

import app.mauri.cucuto.packspec.media.AudioProbe
import app.mauri.cucuto.packspec.media.ImageProbe
import app.mauri.cucuto.packspec.media.SvgSafety
import app.mauri.cucuto.packspec.zip.RandomAccessInput
import app.mauri.cucuto.packspec.zip.RawInflater
import app.mauri.cucuto.packspec.zip.ZipArchive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull

/** A pack found in a validated source. [root] is "" for a single pack, or its folder inside a bundle. */
data class PackEntry(val root: String, val manifest: PackManifest)

/** What a validated source contains. */
sealed interface PackContent {
    val packs: List<PackEntry>

    data class Single(val pack: PackEntry) : PackContent {
        override val packs: List<PackEntry> get() = listOf(pack)
    }

    data class Bundle(val manifest: BundleManifest, override val packs: List<PackEntry>) : PackContent
}

/** Validation result: the report, plus the parsed content when the manifests were readable. */
class PackValidation(val report: ValidationReport, val content: PackContent?, val durationsMs: Map<String, Long>)

/**
 * The pack importer rules (docs/pack-format.md), shared by packer and the Android importer (android/core/pack). Stages run in order and
 * stop after the first stage that reports an error, so every implementation reports the same codes.
 */
class PackValidator(private val checkMedia: Boolean = true) {

    /** Validates a pack or bundle archive (any `.zip`; the type comes from the manifest at the root, not the file name). */
    fun validateArchive(input: RandomAccessInput, inflater: RawInflater): PackValidation {
        val opened = ZipArchive.open(input, inflater)
        val archive = opened.source
        if (archive == null || opened.issues.any { it.severity == Severity.ERROR }) {
            return PackValidation(ValidationReport(opened.issues), null, emptyMap())
        }
        return validate(archive, opened.issues)
    }

    /** Validates an unpacked source (folder, memory, or an already opened archive). */
    fun validate(source: PackSource, initialIssues: List<Issue> = emptyList()): PackValidation {
        val run = Run(source)
        run.sink.issues += initialIssues
        val content = run.execute()
        return PackValidation(run.sink.report(), content, run.durations)
    }

    /** A [MediaReference] resolved against the source root. */
    private class Reference(val path: String, val role: MediaRole, val pointer: String, val manifestFile: String, val ringtone: Ringtone? = null)

    private inner class Run(val source: PackSource) {
        val sink = IssueSink()
        val durations = mutableMapOf<String, Long>()
        val files = linkedMapOf<String, FileFormat>()

        fun execute(): PackContent? {
            sink.beginStage()
            val isBundle = layout() ?: return null
            if (sink.stageFailed()) return null

            sink.beginStage()
            val content = manifests(isBundle)
            if (sink.stageFailed() || content == null) return null

            sink.beginStage()
            val refs = references(content)
            if (sink.stageFailed() || !checkMedia) return content

            sink.beginStage()
            media(refs)
            return content
        }

        // ---------------------------------------------------------------- layout

        /** Returns true for a bundle, false for a pack, null when no manifest decision is possible. */
        fun layout(): Boolean? {
            val seen = mutableSetOf<String>()
            var total = 0L
            for (e in source.entries) {
                val path = e.path
                when {
                    PackPaths.isUnsafe(path) -> { sink.add(ErrorCode.UNSAFE_PATH, "Unsafe entry path", file = path); continue }
                    e.kind == EntryKind.DIRECTORY -> continue
                    PackPaths.isIgnored(path) -> { sink.add(ErrorCode.IGNORED_ENTRY, "Skipped operating system file", file = path); continue }
                    PackPaths.isInvalidName(path) -> { sink.add(ErrorCode.INVALID_FILE_NAME, "Use only A-Z a-z 0-9 . _ - in names, at most ${Limits.MAX_PATH_LENGTH} characters and ${Limits.MAX_PATH_DEPTH} levels", file = path); continue }
                    e.kind != EntryKind.FILE -> { sink.add(ErrorCode.UNSAFE_ENTRY, "Links and special files are not allowed", file = path); continue }
                    !seen.add(path.lowercase()) -> { sink.add(ErrorCode.DUPLICATE_ENTRY, "Another entry has the same path (case-insensitive)", file = path); continue }
                    FileFormat.isExecutableExtension(path) -> { sink.add(ErrorCode.EXECUTABLE_FILE, "Executable or script files are not allowed", file = path); continue }
                }
                val format = FileFormat.of(path)
                val name = path.substringAfterLast('/')
                if (format == null || (format == FileFormat.JSON && name != "pack.json" && name != "bundle.json")) {
                    sink.add(ErrorCode.UNKNOWN_FILE_TYPE, "File type is not allowed in packs", file = path)
                    continue
                }
                if (e.size > format.maxBytes) {
                    sink.add(ErrorCode.FILE_TOO_LARGE, "${e.size} bytes; ${format.name} files may be at most ${format.maxBytes} bytes", file = path)
                    continue
                }
                if (format.kind == FileKind.AUDIO || format.kind == FileKind.IMAGE) {
                    val head = try {
                        source.readHead(path, 8)
                    } catch (ex: PackReadException) {
                        sink.add(ex.code, ex.message ?: "Unreadable", file = path); continue
                    }
                    if (ExecutableSignatures.matches(head)) {
                        sink.add(ErrorCode.EXECUTABLE_FILE, "Media file starts with an executable signature", file = path)
                        continue
                    }
                }
                total += e.size
                files[path] = format
            }
            val hasPack = "pack.json" in files
            val hasBundle = "bundle.json" in files
            if (!hasPack && !hasBundle) {
                sink.add(ErrorCode.MANIFEST_MISSING, "No pack.json or bundle.json at the root")
                return null
            }
            if (hasPack && hasBundle) {
                sink.add(ErrorCode.MANIFEST_AMBIGUOUS, "Both pack.json and bundle.json are at the root")
                return null
            }
            val isBundle = hasBundle
            val maxFiles = if (isBundle) Limits.ENTRIES_PER_ARCHIVE else Limits.FILES_PER_PACK
            if (files.size > maxFiles) sink.add(ErrorCode.TOO_MANY_FILES, "${files.size} files; at most $maxFiles are allowed")
            val maxBytes = if (isBundle) Limits.BUNDLE_UNPACKED_BYTES else Limits.PACK_UNPACKED_BYTES
            if (total > maxBytes) {
                sink.add(ErrorCode.UNPACKED_TOO_LARGE, "$total bytes unpacked; at most $maxBytes are allowed")
            } else if (total > Limits.LARGE_PACK_WARNING_BYTES) {
                sink.add(ErrorCode.LARGE_PACK, "$total bytes unpacked")
            }
            return isBundle
        }

        // ---------------------------------------------------------------- manifests

        fun readManifest(path: String, kind: DocumentKind): JsonElement? {
            val bytes = try {
                source.read(path, Limits.MANIFEST_BYTES)
            } catch (e: PackReadException) {
                sink.add(if (e.code == ErrorCode.FILE_TOO_LARGE) ErrorCode.MANIFEST_INVALID_JSON else e.code, e.message ?: "Unreadable", file = path)
                return null
            }
            val element = parseDocument(bytes, path, sink) ?: return null
            if (!checkFormatVersion(element, path, sink)) return null
            val issues = SpecSchemas.validate(kind, element, path)
            sink.issues += issues
            return if (issues.isEmpty()) element else null
        }

        fun manifests(isBundle: Boolean): PackContent? {
            if (!isBundle) {
                val element = readManifest("pack.json", DocumentKind.PACK) ?: return null
                return PackContent.Single(PackEntry("", SpecJson.decodeFromJsonElement<PackManifest>(element)))
            }
            val bundleElement = readManifest("bundle.json", DocumentKind.BUNDLE) ?: return null
            val bundle = SpecJson.decodeFromJsonElement<BundleManifest>(bundleElement)
            val packs = mutableListOf<PackEntry>()
            for (root in bundle.packs) {
                val manifestPath = "$root/pack.json"
                if (manifestPath !in files) continue // BUNDLE_PACK_MISSING in the references stage
                val element = readManifest(manifestPath, DocumentKind.PACK) ?: continue
                packs += PackEntry(root, SpecJson.decodeFromJsonElement<PackManifest>(element))
            }
            return PackContent.Bundle(bundle, packs)
        }

        // ---------------------------------------------------------------- references

        fun references(content: PackContent): List<Reference> {
            val refs = mutableListOf<Reference>()
            when (content) {
                is PackContent.Single -> {
                    refs += packReferences(content.pack)
                }
                is PackContent.Bundle -> {
                    val b = content.manifest
                    b.packs.forEachIndexed { i, root ->
                        val pointer = "/packs/$i"
                        if ("$root/bundle.json" in files) {
                            sink.add(ErrorCode.BUNDLE_LAYOUT_INVALID, "Bundles cannot contain bundles", file = "bundle.json", pointer = pointer)
                        }
                        val overlaps = b.packs.any { other -> other != root && root.startsWith("$other/") }
                        if (overlaps) sink.add(ErrorCode.BUNDLE_LAYOUT_INVALID, "Pack folder $root is inside another pack folder", file = "bundle.json", pointer = pointer)
                        if ("$root/pack.json" !in files) {
                            sink.add(ErrorCode.BUNDLE_PACK_MISSING, "No pack.json in $root", file = "bundle.json", pointer = pointer)
                        }
                    }
                    duplicates(content.packs.map { it.manifest.id }).forEach { id ->
                        sink.add(ErrorCode.DUPLICATE_ID, "Pack ID $id appears more than once", file = "bundle.json")
                    }
                    refs += b.mediaReferences().map { Reference(it.path, it.role, it.pointer, "bundle.json") }
                    b.license?.let { checkLicense(it, "bundle.json") }
                    for (pack in content.packs) {
                        refs += packReferences(pack)
                        val packFiles = files.filterKeys { it.startsWith(pack.root + "/") }
                        if (packFiles.size > Limits.FILES_PER_PACK) {
                            sink.add(ErrorCode.TOO_MANY_FILES, "${packFiles.size} files; at most ${Limits.FILES_PER_PACK} per pack", file = pack.root)
                        }
                        val bytes = source.entries.filter { it.path in packFiles }.sumOf { it.size }
                        if (bytes > Limits.PACK_UNPACKED_BYTES) {
                            sink.add(ErrorCode.UNPACKED_TOO_LARGE, "$bytes bytes unpacked in ${pack.root}", file = pack.root)
                        }
                    }
                }
            }
            // Missing files and kind mismatches.
            for (ref in refs) {
                val format = files[ref.path]
                if (format == null) {
                    sink.add(ErrorCode.FILE_MISSING, "${ref.role.label} file does not exist", file = ref.path, pointer = ref.pointer)
                    continue
                }
                if (!ref.role.accepts(format)) sink.add(ErrorCode.FILE_TYPE_MISMATCH, "${format.name} cannot be used as ${ref.role.label}", file = ref.path, pointer = ref.pointer)
            }
            // Files nobody references.
            val referenced = refs.map { it.path }.toSet()
            for ((path, format) in files) {
                if (format.kind != FileKind.AUDIO && format.kind != FileKind.IMAGE) continue
                if (path !in referenced) {
                    sink.add(ErrorCode.UNREFERENCED_FILE, "Not referenced by any manifest; it will not be installed", file = path)
                }
            }
            return refs
        }

        fun packReferences(entry: PackEntry): List<Reference> {
            val m = entry.manifest
            val manifestFile = PackPaths.join(entry.root, "pack.json")
            fun full(p: String) = PackPaths.join(entry.root, p)
            val refs = m.mediaReferences().map { Reference(full(it.path), it.role, it.pointer, manifestFile, it.ringtone) }
            checkLicense(m.license, manifestFile)
            duplicates(m.characters.map { it.id }).forEach { id ->
                sink.add(ErrorCode.DUPLICATE_ID, "Character ID $id appears more than once", file = manifestFile)
            }
            m.characters.forEachIndexed { ci, c ->
                val cp = "/characters/$ci"
                c.clips.forEachIndexed { i, clip ->
                    clip.`when`?.let { checkWhen(it, manifestFile, "$cp/clips/$i/when") }
                }
                c.ringtones.forEachIndexed { i, r ->
                    val rp = "$cp/ringtones/$i"
                    r.`when`?.let { checkWhen(it, manifestFile, "$rp/when") }
                    r.loopRange?.let { loop ->
                        val start = loop.startMs ?: 0L
                        if (loop.endMs != null && loop.endMs - start < Limits.MIN_LOOP_MS) {
                            sink.add(ErrorCode.LOOP_POINTS_INVALID, "Loop must be at least ${Limits.MIN_LOOP_MS} ms long", file = manifestFile, pointer = "$rp/loop")
                        }
                    }
                }
                c.haptic?.let { checkHaptic(it, manifestFile, "$cp/haptic") }
            }
            return refs
        }

        /** WHEN_INVALID: an empty time range, or a date that is not a calendar day (02-30, 04-31). */
        fun checkWhen(w: When, file: String, pointer: String) {
            w.timeRange?.let { (from, to) ->
                if (from == to) sink.add(ErrorCode.WHEN_INVALID, "Time range starts and ends at $from", file = file, pointer = "$pointer/time")
            }
            w.dateSpans.forEachIndexed { i, span ->
                if (span == null) {
                    val at = if (w.date is JsonArray) "$pointer/date/$i" else "$pointer/date"
                    sink.add(ErrorCode.WHEN_INVALID, "Not a calendar day (MM-DD)", file = file, pointer = at)
                }
            }
        }

        fun checkHaptic(h: Haptic, file: String, pointer: String) {
            if (h.mode != "custom") return
            var previousStart = -1L
            var busyUntil = 0L
            var end = 0L
            h.events.forEachIndexed { i, e ->
                val ep = "$pointer/events/$i"
                if (e.at < previousStart) {
                    sink.add(ErrorCode.HAPTIC_INVALID, "Events must be sorted by 'at'", file = file, pointer = ep)
                } else if (e.at < busyUntil) {
                    sink.add(ErrorCode.HAPTIC_INVALID, "Event starts before the previous envelope ends at $busyUntil ms", file = file, pointer = ep)
                }
                val env = e.envelope
                if (env != null && env.last().intensity != 0.0) {
                    sink.add(ErrorCode.HAPTIC_INVALID, "An envelope must end with intensity 0", file = file, pointer = "$ep/envelope")
                }
                previousStart = maxOf(previousStart, e.at)
                busyUntil = maxOf(busyUntil, e.at + e.durationMs)
                end = maxOf(end, e.at + e.durationMs)
            }
            if (end > Limits.HAPTIC_MAX_MS) {
                sink.add(ErrorCode.HAPTIC_INVALID, "Haptic lasts $end ms; at most ${Limits.HAPTIC_MAX_MS} ms", file = file, pointer = pointer)
            }
        }

        fun checkLicense(license: String, file: String) {
            if (!Spdx.isValidExpression(license)) {
                sink.add(ErrorCode.LICENSE_NOT_SPDX, "'$license' is not an SPDX license expression", file = file, pointer = "/license")
            }
        }

        // ---------------------------------------------------------------- media

        fun media(refs: List<Reference>) {
            for ((path, pathRefs) in refs.groupBy { it.path }) {
                val format = files[path] ?: continue
                if (format.kind == FileKind.AUDIO) {
                    // Audio files can be large: the probes read only the parts the headers point to.
                    val ms = try {
                        source.useFile(path, format.maxBytes) { AudioProbe.durationMs(format, it) }
                    } catch (e: PackReadException) {
                        sink.add(e.code, e.message ?: "Unreadable", file = path)
                        continue
                    }
                    if (ms == null) {
                        sink.add(ErrorCode.MEDIA_UNDECODABLE, "Not a readable ${format.name} file", file = path)
                        continue
                    }
                    durations[path] = ms
                    checkDuration(path, ms, pathRefs)
                    continue
                }
                val bytes = try {
                    source.read(path, format.maxBytes)
                } catch (e: PackReadException) {
                    sink.add(e.code, e.message ?: "Unreadable", file = path)
                    continue
                }
                when {
                    format == FileFormat.SVG -> when (val r = SvgSafety.check(bytes)) {
                        is SvgSafety.Result.Unsafe -> sink.add(ErrorCode.SVG_UNSAFE, r.reason, file = path)
                        is SvgSafety.Result.Malformed -> sink.add(ErrorCode.MEDIA_UNDECODABLE, "Not a readable SVG: ${r.reason}", file = path)
                        SvgSafety.Result.Safe -> {}
                    }
                    format.isBitmap -> {
                        val size = ImageProbe.size(format, bytes)
                        if (size == null) {
                            sink.add(ErrorCode.MEDIA_UNDECODABLE, "Not a readable ${format.name} file", file = path)
                        } else if (size.width > Limits.MAX_BITMAP_SIDE_PX || size.height > Limits.MAX_BITMAP_SIDE_PX) {
                            sink.add(ErrorCode.IMAGE_TOO_LARGE, "${size.width}x${size.height} px; at most ${Limits.MAX_BITMAP_SIDE_PX} px per side", file = path)
                        }
                    }
                }
            }
        }

        fun checkDuration(path: String, ms: Long, refs: List<Reference>) {
            for (ref in refs) {
                sink.issues += DurationRules.check(path, ms, listOf(MediaReference(ref.path, ref.role, ref.pointer, ref.ringtone)), ref.manifestFile)
            }
        }
    }

    companion object {
        internal fun duplicates(ids: List<String>): Set<String> = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }
}

/** Parses a manifest or index: strict UTF-8 without a byte order mark. Reports MANIFEST_INVALID_JSON. */
internal fun parseDocument(bytes: ByteArray, file: String?, sink: IssueSink): JsonElement? {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        sink.add(ErrorCode.MANIFEST_INVALID_JSON, "Remove the UTF-8 byte order mark", file = file)
        return null
    }
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: Exception) {
        sink.add(ErrorCode.MANIFEST_INVALID_JSON, "Not valid UTF-8", file = file)
        return null
    }
    return try {
        SpecJson.parseToJsonElement(text)
    } catch (e: Exception) {
        sink.add(ErrorCode.MANIFEST_INVALID_JSON, "Invalid JSON: ${e.message?.lineSequence()?.firstOrNull()}", file = file)
        null
    }
}

/** FORMAT_VERSION_UNSUPPORTED when formatVersion is an integer above what this reader supports. */
internal fun checkFormatVersion(element: JsonElement, file: String?, sink: IssueSink): Boolean {
    val v = ((element as? JsonObject)?.get("formatVersion") as? JsonPrimitive)
    val n = v?.takeIf { !it.isString }?.longOrNull ?: return true // schema reports missing or malformed values
    if (n > Limits.FORMAT_VERSION) {
        sink.add(ErrorCode.FORMAT_VERSION_UNSUPPORTED, "formatVersion $n is newer than ${Limits.FORMAT_VERSION}; update the app", file = file, pointer = "/formatVersion")
        return false
    }
    return true
}
