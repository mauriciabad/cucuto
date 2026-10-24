package app.mauri.cucuto.xposed.integration

import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.Route
import app.mauri.cucuto.core.routing.RoutingFormatException
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.Voice

/**
 * The routing table reader of the module that shipped in Cucuto 0.1.1 and 0.1.2 (`RoutingCodec` at 4801934, #151),
 * frozen. After an app update that module keeps running in `system_server` until the next reboot and reads every
 * table the new app publishes (ADR 0024), so today's tables must stay readable by it.
 *
 * Copied verbatim except for the object's name and two internals of `:core:routing` at that revision, inlined
 * (`HapticGrid` and `HapticPreset.fromCode`). Never update it to match today's codec: add another frozen reader when a
 * release ships a reader that newer apps must keep serving.
 */
internal object ReleasedRoutingReader {
    private const val MAGIC: String = "cucuto-routing"
    private const val FORMAT_VERSION: Int = 1
    private const val NONE = "-"
    private const val CLIP_HAPTICS = 'h'
    private const val COMPACT = 'z'
    private const val DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** `HapticGrid` at 4801934. */
    private object HapticGrid {
        const val DURATION_STEP_MS: Int = 20
        private const val TOP = 63

        fun value(level: Int): Int = (level * 1000 + TOP / 2) / TOP
    }

    /** `HapticPreset.fromCode` at 4801934. */
    private fun presetOf(code: Char): HapticPreset =
        when (code) {
            'C' -> HapticPreset.CLICK
            'T' -> HapticPreset.TICK
            'L' -> HapticPreset.LOW_TICK
            else -> throw RoutingFormatException("Unknown haptic preset '$code'")
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

    /** A clip haptic from an `H` line: the compact form or a full `c` timeline. */
    private fun parseClipHaptic(raw: String): Haptic.Custom {
        if (raw.isEmpty() || raw[0] != COMPACT) {
            return parseHaptic(raw) as? Haptic.Custom ?: throw RoutingFormatException("A clip haptic must be custom")
        }
        val events = ArrayList<HapticEvent>()
        for (part in raw.substring(1).split(';')) {
            val fields = part.split(',')
            if (fields.size != 3 || fields[1].length > 1 || fields[2].isEmpty() || fields[2].length % 3 != 0) {
                throw RoutingFormatException("Bad compact envelope '$part'")
            }
            val digits = fields[2]
            val points =
                (digits.indices step 3).map { i ->
                    ControlPoint(
                        HapticGrid.value(digit(digits[i])),
                        HapticGrid.value(digit(digits[i + 1])),
                        digit(digits[i + 2]) * HapticGrid.DURATION_STEP_MS,
                    )
                }
            val initial =
                if (fields[1].isEmpty()) {
                    HapticEvent.Envelope.DEFAULT_SHARPNESS
                } else {
                    HapticGrid.value(digit(fields[1][0]))
                }
            events += HapticEvent.Envelope(fields[0].toInt(), points, initial)
        }
        return Haptic.Custom(events)
    }

    private fun digit(c: Char): Int {
        val value = DIGITS.indexOf(c)
        if (value < 0) throw RoutingFormatException("Bad digit '$c'")
        return value
    }

    private fun parseHaptic(raw: String): Haptic {
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
                            presetOf(values[1][0]),
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

    private class Header(
        val generation: Long,
        val enabled: Boolean,
        val packages: Int,
        val voices: Int,
        val routes: Int,
        val clipHaptics: Int,
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
                    // Added with the clip haptic dictionary; tables written before it have no H lines.
                    clipHaptics = if (pos < s.length && s[pos] != '\n') count() else 0,
                )
            endLine()
            return header
        }

        fun parse(): RoutingTable {
            val header = header()
            val packages = ArrayList<String>(header.packages)
            val voices = ArrayList<Voice>(header.voices)
            val routes = ArrayList<Route>(header.routes)
            var selfTestClip: String? = null
            val clipHaptics = ArrayList<Haptic.Custom>(header.clipHaptics)
            while (pos < s.length) {
                when (s[pos]) {
                    'S' -> {
                        type()
                        selfTestClip = string()
                        endLine()
                    }

                    'P' -> {
                        type()
                        packages += string()
                        endLine()
                    }

                    'H' -> {
                        type()
                        clipHaptics += parseClipHaptic(field())
                        endLine()
                    }

                    'V' -> {
                        type()
                        voices += voice(clipHaptics)
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
            if (packages.size != header.packages ||
                voices.size != header.voices ||
                routes.size != header.routes ||
                clipHaptics.size != header.clipHaptics
            ) {
                throw RoutingFormatException(
                    "Counts differ from the header: " +
                        "${packages.size}/${voices.size}/${routes.size}/${clipHaptics.size}, expected " +
                        "${header.packages}/${header.voices}/${header.routes}/${header.clipHaptics}",
                )
            }
            return RoutingTable(header.generation, header.enabled, voices, routes, selfTestClip)
        }

        private fun voice(dictionary: List<Haptic.Custom>): Voice {
            val packId = string()
            val characterId = string()
            val haptic = parseHaptic(field())
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
                            parseHaptic(raw) as? Haptic.Custom
                                ?: throw RoutingFormatException("A clip haptic must be custom")
                        }
                    }
                clips += Clip(uri, weight, clipHaptic)
            }
            // Trailing fields: the clip haptic references, and anything a newer writer added (skipped).
            while (pos < s.length && s[pos] != '\n') {
                val extra = field()
                if (extra.isEmpty() || extra[0] != CLIP_HAPTICS) continue
                val refs = extra.substring(1).split(',')
                if (refs.size != count) throw RoutingFormatException("Expected $count clip haptics, found ${refs.size}")
                refs.forEachIndexed { i, ref ->
                    if (ref != NONE) {
                        val index = ref.toInt()
                        if (index !in dictionary.indices) throw RoutingFormatException("Unknown clip haptic $index")
                        clips[i] = clips[i].copy(haptic = dictionary[index])
                    }
                }
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
