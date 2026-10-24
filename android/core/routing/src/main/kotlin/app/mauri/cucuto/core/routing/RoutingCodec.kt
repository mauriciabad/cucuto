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
 * cucuto-routing⇥1⇥<generation>⇥<enabled 0|1>⇥<packages>⇥<voices>⇥<routes>⇥<clip haptics>[⇥<conditions>]
 * S⇥<self-test clip URI>                                        optional, at most once
 * P⇥<package>                                                   one per package, referenced by index
 * H⇥<clip haptic>                                               one per distinct clip envelope, referenced by index
 * W⇥<fromMs>⇥<untilMs>⇥<active at from 0|1>⇥<toggle deltas in s, comma-separated, or ->   one per clip condition
 * D⇥<shift s>⇥<transition s>:<shift s>,… or -                  optional, at most once: where local days start
 * V⇥<packId>⇥<characterId>⇥<haptic>⇥<uriBase>⇥<clips>{⇥<uriSuffix>⇥<milliWeight>⇥<clipHaptic|->}[⇥h<i|->,…][⇥w<i|->,…][⇥m<i>:<moment>,…]
 * R⇥<package index>⇥<key>⇥<voice index|->⇥<seed>⇥<flags>⇥<contactId>
 * ```
 *
 * Lines end with `\n`, fields are separated by tabs, and strings escape only `%`, tab, CR and LF as `%25`, `%09`,
 * `%0D`, `%0A`. Haptics: `k` keep, `o` off, `a` auto, or `c` followed by `;`-separated events (`p<at>,<C|T|L>,<scale>`
 * or `e<at>,<initialSharpness or empty>,<intensity>,<sharpness>,<ms>…`), all integers (thousandths for fractions).
 *
 * Clip haptics (auto envelopes) are stored once each in `H` lines and referenced from the trailing `h` field of the
 * voices that use them; the inline clip haptic fields stay `-`. A version 1 reader from before the dictionary skips
 * `H` lines and trailing fields, so it still reads such a table, just without clip envelopes (auto then keeps
 * WhatsApp's vibration). `H` lines use the full syntax or, for envelopes on [HapticGrid], a compact one:
 * `z<at>,<initial sharpness level or empty>,<3 base-64 digits per point: intensity, sharpness, duration steps>`.
 *
 * Clip conditions ([TimeCondition], ADR 0037) work the same way: `W` lines list them, a trailing `w` field on a
 * voice refers to them per clip, and the header's ninth count (written only when there are conditions) says how
 * many. Toggle instants are whole seconds, written as deltas from the previous one (the first from `fromMs`). A
 * reader from before them skips both, and plays every clip of the voice whatever the time.
 *
 * Special clips ([Moment], ADR 0059) are listed sparsely in a trailing `m` field: `<clip index>:<moment>` pairs, where
 * a moment is `f` (first of the day), `b<n>` (the nth message of a burst) and `r<n>` (one in n), in that order. The
 * optional `D` line says where local days start ([LocalDays]). A reader from before them skips both and draws special
 * clips like any other.
 *
 * Readers reject a higher format version and skip line types they do not know, so additive changes can stay in
 * version 1. Parsing is a single pass with no regular expressions, reflection or dependencies, so it runs cheaply
 * inside `system_server` whenever the app republishes (never on the notification path).
 */
public object RoutingCodec {
    public const val MAGIC: String = "cucuto-routing"
    public const val FORMAT_VERSION: Int = 1

    private const val NONE = "-"
    private const val CLIP_HAPTICS = 'h'
    private const val CLIP_CONDITIONS = 'w'
    private const val CLIP_MOMENTS = 'm'
    private const val FIRST_OF_DAY = 'f'
    private const val BURST = 'b'
    private const val ONE_IN = 'r'
    private const val MILLIS_PER_SECOND = 1000L
    private const val COMPACT = 'z'

    /** Base-64 digits of the compact clip haptic form: none of them is a separator or needs escaping. */
    private const val DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    public fun encode(table: RoutingTable): String {
        val packages = LinkedHashMap<String, Int>()
        for (route in table.routes) packages.getOrPut(route.packageName) { packages.size }
        val clipHaptics = LinkedHashMap<Haptic.Custom, Int>()
        for (voice in table.voices) {
            for (clip in voice.clips) clip.haptic?.let { clipHaptics.getOrPut(it) { clipHaptics.size } }
        }

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
            .tab()
            .append(clipHaptics.size)
        if (table.conditions.isNotEmpty()) out.tab().append(table.conditions.size)
        out.append('\n')

        table.selfTestClip?.let { uri ->
            out
                .append('S')
                .tab()
                .escaped(uri)
                .append('\n')
        }

        for (name in packages.keys) {
            out
                .append('P')
                .tab()
                .escaped(name)
                .append('\n')
        }

        for (haptic in clipHaptics.keys) {
            out.append('H').tab()
            if (HapticGrid.isOnGrid(haptic)) writeCompact(out, haptic) else writeHaptic(out, haptic)
            out.append('\n')
        }

        for (condition in table.conditions) writeCondition(out, condition)
        table.days?.let { writeDays(out, it) }

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
                    .append(NONE)
            }
            if (voice.clips.any { it.haptic != null }) {
                out.tab().append(CLIP_HAPTICS)
                voice.clips.forEachIndexed { i, clip ->
                    if (i > 0) out.append(',')
                    val haptic = clip.haptic
                    if (haptic == null) out.append(NONE) else out.append(clipHaptics.getValue(haptic))
                }
            }
            if (voice.isConditional) {
                out.tab().append(CLIP_CONDITIONS)
                voice.clips.forEachIndexed { i, clip ->
                    if (i > 0) out.append(',')
                    if (clip.condition == Clip.ALWAYS) out.append(NONE) else out.append(clip.condition)
                }
            }
            if (voice.hasMoments) {
                out.tab().append(CLIP_MOMENTS)
                var first = true
                voice.clips.forEachIndexed { i, clip ->
                    val moment = clip.moment
                    if (!moment.isSpecial) return@forEachIndexed
                    if (!first) out.append(',')
                    first = false
                    out.append(i).append(':')
                    if (moment.firstOfDay) out.append(FIRST_OF_DAY)
                    if (moment.burst != 0) out.append(BURST).append(moment.burst)
                    if (moment.oneIn != 0) out.append(ONE_IN).append(moment.oneIn)
                }
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

    /**
     * Encodes one haptic in the table's haptic syntax (`k`, `o`, `a` or a `c…` timeline), for storing haptics
     * outside a table, such as the per-clip auto haptics written at import (`:core:haptics`).
     */
    public fun encodeHaptic(haptic: Haptic): String = StringBuilder(32).also { writeHaptic(it, haptic) }.toString()

    /** Decodes [encodeHaptic]'s output, throwing [RoutingFormatException] on any problem. */
    public fun decodeHaptic(encoded: String): Haptic =
        try {
            parseHaptic(encoded)
        } catch (e: RoutingFormatException) {
            throw e
        } catch (e: RuntimeException) {
            throw RoutingFormatException("Malformed haptic '$encoded': ${e.message}", e)
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

    /** `W⇥<from>⇥<until>⇥<0|1>⇥<deltas|->`: toggles as whole-second deltas, each from the previous instant. */
    private fun writeCondition(
        out: StringBuilder,
        condition: TimeCondition,
    ) {
        out
            .append('W')
            .tab()
            .append(condition.fromMs)
            .tab()
            .append(condition.untilMs)
            .tab()
            .append(if (condition.activeAtFrom) '1' else '0')
            .tab()
        if (condition.toggleCount == 0) out.append(NONE)
        var previous = condition.fromMs
        for (i in 0 until condition.toggleCount) {
            val toggle = condition.toggle(i)
            val delta = toggle - previous
            require(delta % MILLIS_PER_SECOND == 0L) { "Condition toggles must be whole seconds apart" }
            if (i > 0) out.append(',')
            out.append(delta / MILLIS_PER_SECOND)
            previous = toggle
        }
        out.append('\n')
    }

    /** `D⇥<shift>⇥<at>:<shift>,…|-`: seconds, transitions as epoch seconds. */
    private fun writeDays(
        out: StringBuilder,
        days: LocalDays,
    ) {
        out
            .append('D')
            .tab()
            .append(days.shiftAtStart)
            .tab()
        if (days.transitionCount == 0) out.append(NONE)
        for (i in 0 until days.transitionCount) {
            val at = days.transition(i)
            require(at % MILLIS_PER_SECOND == 0L) { "Day transitions must be whole seconds" }
            if (i > 0) out.append(',')
            out.append(at / MILLIS_PER_SECOND).append(':').append(days.shift(i))
        }
        out.append('\n')
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

    /** The compact form of an on-grid clip haptic ([HapticGrid.isOnGrid]): `z<at>,<initial>,<points>[;…]`. */
    private fun writeCompact(
        out: StringBuilder,
        haptic: Haptic.Custom,
    ) {
        out.append(COMPACT)
        haptic.events.forEachIndexed { i, event ->
            event as HapticEvent.Envelope
            if (i > 0) out.append(';')
            out.append(event.atMs).append(',')
            if (event.milliInitialSharpness != HapticEvent.Envelope.DEFAULT_SHARPNESS) {
                out.append(DIGITS[HapticGrid.level(event.milliInitialSharpness)])
            }
            out.append(',')
            for (p in event.points) {
                out
                    .append(DIGITS[HapticGrid.level(p.milliIntensity)])
                    .append(DIGITS[HapticGrid.level(p.milliSharpness)])
                    .append(DIGITS[p.durationMs / HapticGrid.DURATION_STEP_MS])
            }
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
        val clipHaptics: Int,
        val conditions: Int,
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
                    clipHaptics = optionalCount(),
                    // Added with clip conditions (ADR 0037); written only when there are some.
                    conditions = optionalCount(),
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
            val conditions = ArrayList<TimeCondition>(header.conditions)
            var days: LocalDays? = null
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

                    'W' -> {
                        type()
                        conditions += condition()
                        endLine()
                    }

                    'D' -> {
                        type()
                        days = days()
                        endLine()
                    }

                    'V' -> {
                        type()
                        voices += voice(clipHaptics, conditions.size)
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
                clipHaptics.size != header.clipHaptics ||
                conditions.size != header.conditions
            ) {
                throw RoutingFormatException(
                    "Counts differ from the header: " +
                        "${packages.size}/${voices.size}/${routes.size}/${clipHaptics.size}/${conditions.size}, " +
                        "expected ${header.packages}/${header.voices}/${header.routes}/${header.clipHaptics}/" +
                        "${header.conditions}",
                )
            }
            return RoutingTable(header.generation, header.enabled, voices, routes, selfTestClip, conditions, days)
        }

        private fun condition(): TimeCondition {
            val from = long()
            val until = long()
            val active = int() != 0
            val raw = field()
            if (raw == NONE) return TimeCondition(from, until, active, LongArray(0))
            val deltas = raw.split(',')
            val toggles = LongArray(deltas.size)
            var previous = from
            deltas.forEachIndexed { i, delta ->
                previous += delta.toLong() * MILLIS_PER_SECOND
                toggles[i] = previous
            }
            return TimeCondition(from, until, active, toggles)
        }

        private fun days(): LocalDays {
            val start = int()
            val raw = field()
            if (raw == NONE) return LocalDays(start)
            val parts = raw.split(',')
            if (parts.size > LocalDays.MAX_TRANSITIONS) throw RoutingFormatException("Too many day transitions")
            val transitions = LongArray(parts.size)
            val shifts = IntArray(parts.size)
            parts.forEachIndexed { i, part ->
                val colon = part.indexOf(':')
                if (colon <= 0) throw RoutingFormatException("Bad day transition '$part'")
                transitions[i] = part.substring(0, colon).toLong() * MILLIS_PER_SECOND
                shifts[i] = part.substring(colon + 1).toInt()
            }
            return LocalDays(start, transitions, shifts)
        }

        private fun voice(
            dictionary: List<Haptic.Custom>,
            conditionCount: Int,
        ): Voice {
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
            // Trailing fields: clip haptic and condition references, and anything a newer writer added (skipped).
            while (pos < s.length && s[pos] != '\n') {
                val extra = field()
                if (extra.isNotEmpty() && extra[0] == CLIP_CONDITIONS) {
                    readConditionRefs(extra, clips, conditionCount)
                    continue
                }
                if (extra.isNotEmpty() && extra[0] == CLIP_MOMENTS) {
                    readMoments(extra, clips)
                    continue
                }
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

        private fun readConditionRefs(
            field: String,
            clips: MutableList<Clip>,
            conditionCount: Int,
        ) {
            val refs = field.substring(1).split(',')
            if (refs.size != clips.size) {
                throw RoutingFormatException("Expected ${clips.size} clip conditions, found ${refs.size}")
            }
            refs.forEachIndexed { i, ref ->
                if (ref != NONE) {
                    val index = ref.toInt()
                    if (index !in 0 until conditionCount) throw RoutingFormatException("Unknown clip condition $index")
                    clips[i] = clips[i].copy(condition = index)
                }
            }
        }

        /** `m<index>:<moment>,…`: only the special clips, in increasing index order. */
        private fun readMoments(
            field: String,
            clips: MutableList<Clip>,
        ) {
            if (field.length == 1) return
            var previous = -1
            for (pair in field.substring(1).split(',')) {
                val colon = pair.indexOf(':')
                if (colon <= 0) throw RoutingFormatException("Bad clip moment '$pair'")
                val index = pair.substring(0, colon).toInt()
                if (index <= previous ||
                    index >= clips.size
                ) {
                    throw RoutingFormatException("Bad clip moment index $index")
                }
                previous = index
                clips[index] = clips[index].copy(moment = parseMoment(pair, colon + 1))
            }
        }

        private fun parseMoment(
            raw: String,
            start: Int,
        ): Moment {
            var i = start
            var firstOfDay = false
            var burst = 0
            var oneIn = 0
            while (i < raw.length) {
                val kind = raw[i++]
                if (kind == FIRST_OF_DAY) {
                    firstOfDay = true
                    continue
                }
                val digits = i
                while (i < raw.length && raw[i].isDigit()) i++
                val value = raw.substring(digits, i).toInt()
                when (kind) {
                    BURST -> burst = value
                    ONE_IN -> oneIn = value
                    else -> throw RoutingFormatException("Unknown clip moment '$kind' in '$raw'")
                }
            }
            val moment = Moment(firstOfDay, burst, oneIn)
            if (!moment.isSpecial) throw RoutingFormatException("Empty clip moment in '$raw'")
            return moment
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

        /** A header count added after the first version of the format: 0 when the line ends before it. */
        private fun optionalCount(): Int = if (pos < s.length && s[pos] != '\n') count() else 0

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
