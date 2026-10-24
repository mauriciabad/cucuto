package app.mauri.cucuto.packspec.authoring

import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.SemVer
import kotlin.random.Random

/**
 * The conventions the pack creators (the app's, #47, and the website's, #48) share, so a pack made in either looks the
 * same and imports everywhere: how names become IDs and file names, the version a new pack starts at, the license
 * presets, and the audio and image targets the creators encode to. See docs/decisions/0034-in-app-pack-creator.md.
 */
object Authoring {
    /** The version of a new pack. */
    const val FIRST_VERSION = "1.0.0"

    /** License presets offered first (brief: "with CC0 and CC-BY presets"); anything else is free text. */
    val LICENSE_PRESETS: List<String> = listOf("CC0-1.0", "CC-BY-4.0")

    /**
     * The license a new pack starts with: free text (a `LICENSE_NOT_SPDX` warning, never an error). People often make
     * packs from clips and pictures they did not make, so the creators never suggest public domain on their behalf.
     */
    const val PERSONAL_USE_LICENSE = "Personal use only"

    /** First label of the IDs the creators suggest: the person has no domain to use (docs/pack-format.md#ids). */
    const val PACK_ID_PREFIX = "local"

    /** Length of the random part that keeps two packs with the same author and name apart. */
    const val PACK_ID_SUFFIX_LENGTH = 6

    /** Longest slug in an ID or file name (the schema's slug limit). */
    const val MAX_SLUG_LENGTH = 64

    /** Shown when a name has no letters or digits that fit a slug (for example a name in Japanese). */
    const val FALLBACK_CHARACTER_SLUG = "character"
    const val FALLBACK_CLIP_SLUG = "clip"
    private const val FALLBACK_AUTHOR_SLUG = "me"
    private const val FALLBACK_PACK_SLUG = "pack"
    private const val MAX_AUTHOR_SLUG = 40
    private const val MAX_PACK_SLUG = 60

    /**
     * Lowercase `[a-z0-9]` words joined by single hyphens (the schema's slug), at most [maxLength] characters, cut at a
     * word boundary when possible. Latin letters lose their accents (`Pokémon` → `pokemon`); other characters
     * separate words. Empty when nothing usable is left.
     */
    fun slug(
        text: String,
        maxLength: Int = MAX_SLUG_LENGTH,
    ): String {
        val out = StringBuilder()
        var pendingDash = false
        for (ch in text.lowercase()) {
            val mapped =
                when (ch) {
                    in 'a'..'z', in '0'..'9' -> ch.toString()
                    else -> FOLDED[ch]
                }
            if (mapped == null) {
                pendingDash = true
                continue
            }
            if (pendingDash && out.isNotEmpty()) out.append('-')
            pendingDash = false
            out.append(mapped)
        }
        if (out.length <= maxLength) return out.toString()
        val cut = out.substring(0, maxLength)
        val lastDash = cut.lastIndexOf('-')
        return (if (lastDash > maxLength / 2) cut.substring(0, lastDash) else cut).trimEnd('-')
    }

    /** [base], or [base]-2, [base]-3… (the first not in [taken]); [fallback] when [base] is empty. */
    fun uniqueSlug(
        base: String,
        taken: Set<String>,
        fallback: String,
    ): String {
        val root = base.ifEmpty { fallback }
        if (root !in taken) return root
        var n = 2
        while (true) {
            val suffix = "-$n"
            val candidate = root.take(MAX_SLUG_LENGTH - suffix.length).trimEnd('-') + suffix
            if (candidate !in taken) return candidate
            n++
        }
    }

    /** A random [PACK_ID_SUFFIX_LENGTH]-character `[a-z0-9]` string for [suggestPackId]. Pick it once per pack. */
    fun randomIdSuffix(random: Random = Random.Default): String =
        buildString { repeat(PACK_ID_SUFFIX_LENGTH) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) } }

    /**
     * The ID a creator suggests for a new pack: `local.<author>.<name>-<suffix>`, for example
     * `local.ana.garden-birds-k3x9q2`. [suffix] (from [randomIdSuffix], fixed for the life of the pack) keeps two packs
     * with the same author and name apart. Matches the schema's pack ID pattern and length by construction.
     */
    fun suggestPackId(
        author: String,
        name: String,
        suffix: String,
    ): String {
        val who = slug(author, MAX_AUTHOR_SLUG).ifEmpty { FALLBACK_AUTHOR_SLUG }
        val what = slug(name, MAX_PACK_SLUG).ifEmpty { FALLBACK_PACK_SLUG }
        val tail = slug(suffix, PACK_ID_SUFFIX_LENGTH).ifEmpty { FALLBACK_PACK_SLUG }
        return "$PACK_ID_PREFIX.$who.$what-$tail"
    }

    /**
     * The file name creators suggest for a pack: `<name slug>.soundpack.zip`, or the last label of [id] when the name
     * has no usable letters (ADR 0007: pack files always end in `.zip`).
     */
    fun packFileName(
        name: String,
        id: String,
    ): String = slug(name).ifEmpty { id.substringAfterLast('.') } + PACK_FILE_SUFFIX

    /** What every pack file name ends with. */
    const val PACK_FILE_SUFFIX = ".soundpack.zip"

    /**
     * The version to write when [current] would not install over [installed] as an update: one patch above the
     * installed version (or the release of an installed pre-release). [current] otherwise, or when either version is unreadable.
     */
    fun versionAfter(
        current: String,
        installed: String?,
    ): String {
        val mine = SemVer.parse(current) ?: return current
        val theirs = installed?.let(SemVer::parse) ?: return current
        if (mine > theirs) return current
        // A release ranks above its own pre-releases: 2.3.5 updates 2.3.5-beta.1.
        val patch = if (theirs.preRelease.isEmpty()) theirs.patch + 1 else theirs.patch
        return SemVer(theirs.major, theirs.minor, patch, emptyList()).toString()
    }

    /** Most tags a pack or character carries, and the longest tag (the schema's `tags`). */
    const val MAX_TAGS = 20
    const val MAX_TAG_LENGTH = 32

    /**
     * Tags typed as text (`Animals, 8-bit games`): each one becomes a tag of the format (lowercase words joined by
     * hyphens, up to [MAX_TAG_LENGTH] characters: `animals`, `8-bit-games`), duplicates are dropped and at most
     * [MAX_TAGS] are kept.
     */
    fun tags(text: String): List<String> =
        text
            .split(',', ';', '\n')
            .map { slug(it, MAX_TAG_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TAGS)

    /** Whether [url] is a homepage the format accepts: an absolute `https://` URL (blank means none). */
    fun isHomepage(url: String): Boolean = HTTPS_URL.matches(url.trim())

    /** Audio targets of the creators; the same as `packer --normalize --trim-silence --opus` for clips. */
    object Audio {
        /** Opus runs at 48 kHz; every clip is resampled to it. */
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNELS = 1

        /** Opus bitrate for clips (packer: 96 kb/s for clips, 128 kb/s for ringtones). */
        const val CLIP_BITRATE_BPS = 96_000

        /** Integrated loudness target and true-peak ceiling (EBU R128, packer's loudnorm settings). */
        const val TARGET_LUFS = -16.0
        const val TRUE_PEAK_CEILING_DBTP = -1.5

        /** Never boost a quiet recording by more than this: it would mostly raise the noise. */
        const val MAX_GAIN_DB = 24.0

        /** Leading and trailing audio below this is silence (packer: `silenceremove … -50dB`). */
        const val SILENCE_THRESHOLD_DBFS = -50.0

        /** Silence kept before the first and after the last sound when trimming (packer: `start_silence=0.02`). */
        const val SILENCE_KEEP_MS = 20L

        /** Short fades at both ends of a cut, so it never clicks. */
        const val EDGE_FADE_MS = 5L

        /** Longest recording: the longest clip a pack accepts. */
        const val MAX_RECORDING_MS = Limits.CLIP_MAX_MS

        /** Longest source the creators open for cutting a clip from (a whole song): the longest ringtone. */
        const val MAX_SOURCE_MS = Limits.RINGTONE_MAX_MS
    }

    /** Image targets of the creators. */
    object Image {
        /** Sprites and covers are cropped square and written at this size (the bitmap limit). */
        const val SPRITE_SIZE_PX = Limits.MAX_BITMAP_SIDE_PX
    }

    private val HTTPS_URL = Regex("^https://[^\\s/?#]+\\S*$")

    private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** Latin letters with diacritics and ligatures, folded to ASCII (common Kotlin has no Unicode normalizer). */
    private val FOLDED: Map<Char, String> =
        buildMap {
            fun fold(
                chars: String,
                to: String,
            ) = chars.forEach { put(it, to) }
            fold("àáâãäåāăąǎȁȃȧạảấầẩẫậắằẳẵặ", "a")
            fold("çćĉċč", "c")
            fold("ďđ", "d")
            fold("èéêëēĕėęěȅȇẹẻẽếềểễệ", "e")
            fold("ĝğġģ", "g")
            fold("ĥħ", "h")
            fold("ìíîïĩīĭįıǐȉȋịỉ", "i")
            fold("ĵ", "j")
            fold("ķ", "k")
            fold("ĺļľŀł", "l")
            fold("ñńņňŉ", "n")
            fold("òóôõöøōŏőǒȍȏọỏốồổỗộớờởỡợ", "o")
            fold("ŕŗř", "r")
            fold("śŝşšș", "s")
            fold("ţťŧț", "t")
            fold("ùúûüũūŭůűųǔȕȗụủứừửữự", "u")
            fold("ŵ", "w")
            fold("ýÿŷỳỵỷỹ", "y")
            fold("źżž", "z")
            put('ß', "ss")
            put('æ', "ae")
            put('œ', "oe")
            put('þ', "th")
            put('ð', "d")
        }
}
