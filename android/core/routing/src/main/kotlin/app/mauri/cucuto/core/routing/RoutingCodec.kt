package app.mauri.cucuto.core.routing

/** The encoded table is malformed or from a newer, unsupported format version. */
public class RoutingFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Compact, versioned text encoding of a [RoutingTable]. Specified in `docs/routing-table.md`; summary:
 *
 * ```
 * cucuto-routing⇥1⇥<generation>⇥<enabled 0|1>⇥<packages>⇥<voices>⇥<routes>
 * P⇥<package>                                                   one per package, referenced by index
 * V⇥<packId>⇥<characterId>⇥<haptic>⇥<uriBase>⇥<clips>{⇥<uriSuffix>⇥<milliWeight>⇥<clipHaptic|->}
 * R⇥<package index>⇥<key>⇥<voice index|->⇥<seed>⇥<flags>⇥<contactId>
 * ```
 *
 * Lines end with `\n`, fields are separated by tabs, and strings escape only `%`, tab, CR and LF as `%25`, `%09`,
 * `%0D`, `%0A`. Haptics: `k` keep, `o` off, `a` auto, or `c` followed by `;`-separated events (`p<at>,<C|T|L>,<scale>`
 * or `e<at>,<initialSharpness or empty>,<intensity>,<sharpness>,<ms>…`), all integers (thousandths for fractions).
 *
 * Readers reject a higher format version and skip line types they do not know, so additive changes can stay in
 * version 1. Parsing is a single pass with no regular expressions, reflection or dependencies, so it runs cheaply
 * inside `system_server` whenever the app republishes (never on the notification path).
 */
public object RoutingCodec {
    public const val MAGIC: String = "cucuto-routing"
    public const val FORMAT_VERSION: Int = 1

    private const val NONE = "-"

    public fun encode(table: RoutingTable): String {
        val packages = LinkedHashMap<String, Int>()
        for (route in table.routes) packages.getOrPut(route.packageName) { packages.size }

        val out = StringBuilder(64 + table.voices.size * 160 + table.routes.size * 48)
        out
            .append(MAGIC)
            .tab()
            .append(FORMAT_VERSION)
            .tab()
            .append(table.generation)
            .tab()
            .append(if (table.enabled) '1' else '0')
            .tab()
            .append(packages.size)
            .tab()
            .append(table.voices.size)
            .tab()
            .append(table.routes.size)
            .append('\n')

        for (name in packages.keys) {
            out
                .append('P')
                .tab()
                .escaped(name)
                .append('\n')
        }

        for (voice in table.voices) {
            val base = commonBase(voice.clips)
            out
                .append('V')
                .tab()
                .escaped(voice.packId)
                .tab()
                .escaped(voice.characterId)
                .tab()
            writeHaptic(out, voice.haptic)
            out
                .tab()
                .escaped(base)
                .tab()
                .append(voice.clips.size)
            for (clip in voice.clips) {
                out
                    .tab()
                    .escaped(clip.uri.substring(base.length))
                    .tab()
                    .append(clip.milliWeight)
                    .tab()
                val haptic = clip.haptic
                if (haptic == null) out.append(NONE) else writeHaptic(out, haptic)
            }
            out.append('\n')
        }

        for (route in table.routes) {
            out
                .append('R')
                .tab()
                .append(packages.getValue(route.packageName))
                .tab()
                .escaped(route.key)
                .tab()
            if (route.voice == Route.NO_VOICE) out.append(NONE) else out.append(route.voice)
            out
                .tab()
                .append(route.seed)
                .tab()
                .append(route.flags)
                .tab()
                .append(route.contactId)
                .append('\n')
        }
        return out.toString()
    }

    /** Decodes [encoded], throwing [RoutingFormatException] on any problem. */
    public fun decode(encoded: String): RoutingTable =
        try {
            Parser(encoded).parse()
        } catch (e: RoutingFormatException) {
            throw e
        } catch (e: RuntimeException) {
            throw RoutingFormatException("Malformed routing table: ${e.message}", e)
        }

    /** Like [decode], but returns null instead of throwing. For the module, which must never throw. */
    public fun decodeOrNull(encoded: String?): RoutingTable? =
        if (encoded.isNullOrEmpty()) {
            null
        } else {
            try {
                decode(encoded)
            } catch (_: RoutingFormatException) {
                null
            }
        }

    /** Reads just the header's generation, or null if [encoded] is not a supported table. */
    public fun peekGeneration(encoded: String?): Long? {
        if (encoded == null || !encoded.startsWith(MAGIC)) return null
        return try {
            val cursor = Parser(encoded)
            cursor.header().generation
        } catch (_: RuntimeException) {
            null
        } catch (_: RoutingFormatException) {
            null
        }
    }

    private fun commonBase(clips: List<Clip>): String {
        if (clips.isEmpty()) return ""
        val first = clips[0].uri
        var length = first.lastIndexOf('/') + 1
        for (i in 1 until clips.size) {
            val uri = clips[i].uri
            var j = 0
            val max = minOf(length, uri.length)
            while (j < max && uri[j] == first[j]) j++
            length = j
        }
        return first.substring(0, first.lastIndexOf('/', length - 1) + 1)
    }

    private fun writeHaptic(
        out: StringBuilder,
        haptic: Haptic,
    ) {
        when (haptic) {
            Haptic.Keep -> {
                out.append('k')
            }

            Haptic.Off -> {
                out.append('o')
            }

            Haptic.Auto -> {
                out.append('a')
            }

            is Haptic.Custom -> {
                out.append('c')
                haptic.events.forEachIndexed { i, event ->
                    if (i > 0) out.append(';')
                    when (event) {
                        is HapticEvent.Preset -> {
                            out
                                .append('p')
                                .append(event.atMs)
                                .append(',')
                                .append(event.preset.code)
                                .append(',')
                                .append(event.milliScale)
                        }

                        is HapticEvent.Envelope -> {
                            out.append('e').append(event.atMs).append(',')
                            if (event.milliInitialSharpness != HapticEvent.Envelope.DEFAULT_SHARPNESS) {
                                out.append(event.milliInitialSharpness)
                            }
                            for (p in event.points) {
                                out
                                    .append(',')
                                    .append(p.milliIntensity)
                                    .append(',')
                                    .append(p.milliSharpness)
                                    .append(',')
                                    .append(p.durationMs)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun StringBuilder.tab(): StringBuilder = append('\t')

    private fun StringBuilder.escaped(value: String): StringBuilder {
        var plain = true
        for (c in value) {
            if (c == '%' || c == '\t' || c == '\n' || c == '\r') {
                plain = false
                break
            }
        }
        if (plain) return append(value)
        for (c in value) {
            when (c) {
                '%' -> append("%25")
                '\t' -> append("%09")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                else -> append(c)
            }
        }
        return this
    }

    private class Header(
        val generation: Long,
        val enabled: Boolean,
        val packages: Int,
        val voices: Int,
        val routes: Int,
    )

    /** Single-pass cursor over the encoded text. */
    private class Parser(
        private val s: String,
    ) {
        private var pos = 0

        fun header(): Header {
            val magic = field()
            if (magic != MAGIC) throw RoutingFormatException("Not a routing table")
            val version = int()
            if (version > FORMAT_VERSION) {
                throw RoutingFormatException("Routing table format $version is newer than $FORMAT_VERSION")
            }
            if (version < 1) throw RoutingFormatException("Invalid routing table format $version")
            val header =
                Header(
                    generation = long(),
                    enabled = int() != 0,
                    packages = count(),
                    voices = count(),
                    routes = count(),
                )
            endLine()
            return header
        }

        fun parse(): RoutingTable {
            val header = header()
            val packages = ArrayList<String>(header.packages)
            val voices = ArrayList<Voice>(header.voices)
            val routes = ArrayList<Route>(header.routes)
            while (pos < s.length) {
                when (s[pos]) {
                    'P' -> {
                        type()
                        packages += string()
                        endLine()
                    }

                    'V' -> {
                        type()
                        voices += voice()
                        endLine()
                    }

                    'R' -> {
                        type()
                        routes += route(packages, header.voices)
                        endLine()
                    }

                    else -> {
                        skipLine()
                    }
                }
            }
            if (packages.size != header.packages || voices.size != header.voices || routes.size != header.routes) {
                throw RoutingFormatException(
                    "Counts differ from the header: ${packages.size}/${voices.size}/${routes.size}, " +
                        "expected ${header.packages}/${header.voices}/${header.routes}",
                )
            }
            return RoutingTable(header.generation, header.enabled, voices, routes)
        }

        private fun voice(): Voice {
            val packId = string()
            val characterId = string()
            val haptic = haptic(field())
            val base = string()
            val count = count()
            val clips = ArrayList<Clip>(count)
            repeat(count) {
                val uri = base + string()
                val weight = int()
                val clipHaptic =
                    field().let { raw ->
                        if (raw == NONE) {
                            null
                        } else {
                            haptic(raw) as? Haptic.Custom
                                ?: throw RoutingFormatException("A clip haptic must be custom")
                        }
                    }
                clips += Clip(uri, weight, clipHaptic)
            }
            return Voice(packId, characterId, clips, haptic)
        }

        private fun route(
            packages: List<String>,
            voiceCount: Int,
        ): Route {
            val packageIndex = int()
            if (packageIndex !in packages.indices) throw RoutingFormatException("Unknown package $packageIndex")
            val key = string()
            val voiceField = field()
            val voice = if (voiceField == NONE) Route.NO_VOICE else voiceField.toInt()
            if (voice != Route.NO_VOICE && voice !in 0 until voiceCount) {
                throw RoutingFormatException("Unknown voice $voice")
            }
            return Route(
                packageName = packages[packageIndex],
                key = key,
                voice = voice,
                seed = int(),
                flags = int(),
                contactId = long(),
            )
        }

        private fun haptic(raw: String): Haptic {
            if (raw.length == 1) {
                when (raw[0]) {
                    'k' -> return Haptic.Keep
                    'o' -> return Haptic.Off
                    'a' -> return Haptic.Auto
                }
            }
            if (raw.isEmpty() || raw[0] != 'c') throw RoutingFormatException("Unknown haptic '$raw'")
            val events = ArrayList<HapticEvent>()
            for (part in raw.substring(1).split(';')) {
                val values = part.substring(1).split(',')
                events +=
                    when (part[0]) {
                        'p' -> {
                            if (values.size != 3 || values[1].length != 1) {
                                throw RoutingFormatException("Bad preset '$part'")
                            }
                            HapticEvent.Preset(
                                values[0].toInt(),
                                HapticPreset.fromCode(values[1][0]),
                                values[2].toInt(),
                            )
                        }

                        'e' -> {
                            if (values.size < 5 || (values.size - 2) % 3 != 0) {
                                throw RoutingFormatException("Bad envelope '$part'")
                            }
                            val points =
                                (2 until values.size step 3).map { i ->
                                    ControlPoint(values[i].toInt(), values[i + 1].toInt(), values[i + 2].toInt())
                                }
                            val sharpness =
                                if (values[1].isEmpty()) HapticEvent.Envelope.DEFAULT_SHARPNESS else values[1].toInt()
                            HapticEvent.Envelope(values[0].toInt(), points, sharpness)
                        }

                        else -> {
                            throw RoutingFormatException("Unknown haptic event '$part'")
                        }
                    }
            }
            return Haptic.Custom(events)
        }

        private fun type() {
            pos++
            if (pos >= s.length || s[pos] != '\t') throw RoutingFormatException("Expected a tab at $pos")
            pos++
        }

        /** Reads up to the next tab or line end, consuming a trailing tab. */
        private fun field(): String {
            if (pos >= s.length) throw RoutingFormatException("Unexpected end of table")
            val start = pos
            while (pos < s.length) {
                val c = s[pos]
                if (c == '\t' || c == '\n') break
                pos++
            }
            val value = s.substring(start, pos)
            if (pos < s.length && s[pos] == '\t') pos++
            return value
        }

        private fun string(): String {
            val raw = field()
            return if (raw.indexOf('%') < 0) raw else unescape(raw)
        }

        private fun int(): Int = field().toInt()

        private fun long(): Long = field().toLong()

        private fun count(): Int = int().also { if (it < 0) throw RoutingFormatException("Negative count") }

        /** Moves past the end of the line. Extra trailing fields (added by a newer writer) are ignored. */
        private fun endLine() = skipLine()

        private fun skipLine() {
            val end = s.indexOf('\n', pos)
            pos = if (end < 0) s.length else end + 1
        }

        private fun unescape(raw: String): String {
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '%') {
                    if (i + 2 >= raw.length) throw RoutingFormatException("Truncated escape in '$raw'")
                    out.append(raw.substring(i + 1, i + 3).toInt(16).toChar())
                    i += 3
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
