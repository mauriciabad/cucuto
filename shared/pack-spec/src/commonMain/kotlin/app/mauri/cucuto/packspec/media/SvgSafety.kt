package app.mauri.cucuto.packspec.media

/**
 * Conservative SVG check. Packs must contain static, self-contained SVG: importers reject (SVG_UNSAFE) rather than
 * silently rewrite, and apps still render SVG with a renderer that has no scripting or network access.
 *
 * Rejected: DOCTYPE and entity declarations, processing instructions other than the XML declaration, the elements
 * script, foreignObject, iframe, embed, object, audio, video, handler and listener, any on* event attribute, href / src
 * values that are not same-document fragments (#id) or raster data: URIs, url(...) references that are not fragments,
 * CSS @import, javascript: anywhere, and animations that target href or event attributes.
 */
object SvgSafety {
    sealed interface Result {
        data object Safe : Result
        data class Unsafe(val reason: String) : Result
        data class Malformed(val reason: String) : Result
    }

    private val BLOCKED_ELEMENTS = setOf("script", "foreignobject", "iframe", "embed", "object", "audio", "video", "handler", "listener")
    private val ANIMATION_ELEMENTS = setOf("set", "animate", "animatemotion", "animatetransform")
    private val URL_ATTRIBUTES = setOf("href", "src")
    private val DATA_IMAGE = Regex("^data:image/(png|jpeg|gif|webp|avif|jxl);base64,", RegexOption.IGNORE_CASE)
    private val CSS_URL = Regex("url\\(\\s*(['\"]?)([^'\")]*)", RegexOption.IGNORE_CASE)
    private val NAME = Regex("[A-Za-z_:][A-Za-z0-9._:-]*")

    fun check(bytes: ByteArray): Result {
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: Exception) {
            return Result.Malformed("not UTF-8")
        }.removePrefix("﻿")
        var root: String? = null
        var depth = 0
        var inStyle = false
        var i = 0
        val n = text.length
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0) {
                if (inStyle) cssProblem(decode(text.substring(i)))?.let { return Result.Unsafe(it) }
                break
            }
            if (inStyle && lt > i) cssProblem(decode(text.substring(i, lt)))?.let { return Result.Unsafe(it) }
            when {
                text.startsWith("<!--", lt) -> {
                    val end = text.indexOf("-->", lt + 4)
                    if (end < 0) return Result.Malformed("unterminated comment")
                    i = end + 3
                }
                text.startsWith("<![CDATA[", lt) -> {
                    val end = text.indexOf("]]>", lt + 9)
                    if (end < 0) return Result.Malformed("unterminated CDATA")
                    if (inStyle) cssProblem(text.substring(lt + 9, end))?.let { return Result.Unsafe(it) }
                    if (text.substring(lt + 9, end).contains("javascript:", ignoreCase = true)) return Result.Unsafe("javascript: URL")
                    i = end + 3
                }
                text.startsWith("<!", lt) -> return Result.Unsafe("DOCTYPE or entity declaration")
                text.startsWith("<?", lt) -> {
                    val end = text.indexOf("?>", lt + 2)
                    if (end < 0) return Result.Malformed("unterminated processing instruction")
                    val target = NAME.matchAt(text, lt + 2)?.value
                    if (target == null || target.lowercase() != "xml" || root != null) return Result.Unsafe("processing instruction <?$target")
                    i = end + 2
                }
                text.startsWith("</", lt) -> {
                    val end = text.indexOf('>', lt)
                    if (end < 0) return Result.Malformed("unterminated end tag")
                    val name = localName(text.substring(lt + 2, end).trim())
                    if (name == "style") inStyle = false
                    depth--
                    i = end + 1
                }
                else -> {
                    val nameMatch = NAME.matchAt(text, lt + 1) ?: return Result.Malformed("bad tag at $lt")
                    val qname = nameMatch.value
                    val name = localName(qname)
                    if (root == null) {
                        if (name != "svg") return Result.Malformed("root element is <$qname>, not <svg>")
                        root = name
                    } else if (depth <= 0) {
                        return Result.Malformed("content after the root element")
                    }
                    if (name in BLOCKED_ELEMENTS) return Result.Unsafe("<$qname> element")
                    var p = nameMatch.range.last + 1
                    var selfClosing = false
                    val attrs = mutableMapOf<String, String>()
                    while (true) {
                        while (p < n && text[p].isWhitespace()) p++
                        if (p >= n) return Result.Malformed("unterminated tag <$qname>")
                        if (text[p] == '>') { p++; break }
                        if (text.startsWith("/>", p)) { selfClosing = true; p += 2; break }
                        val attr = NAME.matchAt(text, p) ?: return Result.Malformed("bad attribute in <$qname>")
                        p = attr.range.last + 1
                        while (p < n && text[p].isWhitespace()) p++
                        if (p >= n || text[p] != '=') return Result.Malformed("attribute ${attr.value} without value")
                        p++
                        while (p < n && text[p].isWhitespace()) p++
                        if (p >= n || (text[p] != '"' && text[p] != '\'')) return Result.Malformed("unquoted attribute ${attr.value}")
                        val quote = text[p]
                        val close = text.indexOf(quote, p + 1)
                        if (close < 0) return Result.Malformed("unterminated attribute ${attr.value}")
                        attrs[attr.value] = decode(text.substring(p + 1, close))
                        p = close + 1
                    }
                    for ((attrName, value) in attrs) {
                        val local = localName(attrName).lowercase()
                        if (local.startsWith("on")) return Result.Unsafe("event attribute $attrName")
                        if (local in URL_ATTRIBUTES && !isSafeReference(value)) return Result.Unsafe("external reference $attrName=\"$value\"")
                        if (value.contains("javascript:", ignoreCase = true)) return Result.Unsafe("javascript: URL in $attrName")
                        cssProblem(value)?.let { return Result.Unsafe("$it in $attrName") }
                    }
                    if (name in ANIMATION_ELEMENTS) {
                        val target = attrs.entries.firstOrNull { localName(it.key).lowercase() == "attributename" }?.value?.let { localName(it).lowercase() }
                        if (target != null && (target in URL_ATTRIBUTES || target.startsWith("on"))) {
                            return Result.Unsafe("<$qname> animates $target")
                        }
                    }
                    if (!selfClosing) {
                        depth++
                        if (name == "style") inStyle = true
                    }
                    i = p
                }
            }
        }
        return when {
            root == null -> Result.Malformed("no <svg> element")
            depth != 0 -> Result.Malformed("unbalanced tags")
            else -> Result.Safe
        }
    }

    private fun localName(qname: String): String = qname.substringAfter(':').lowercase()

    private fun isSafeReference(value: String): Boolean {
        val v = value.trim()
        return v.startsWith("#") || DATA_IMAGE.containsMatchIn(v)
    }

    private fun cssProblem(css: String): String? {
        if (css.contains("@import", ignoreCase = true)) return "CSS @import"
        if (css.contains("javascript:", ignoreCase = true)) return "javascript: URL"
        if (css.contains("expression(", ignoreCase = true)) return "CSS expression()"
        for (m in CSS_URL.findAll(css)) {
            val target = m.groupValues[2].trim()
            if (!target.startsWith("#") && !DATA_IMAGE.containsMatchIn(target)) return "external url($target)"
        }
        return null
    }

    /** Decodes character and predefined entity references so encoded payloads cannot hide. */
    private fun decode(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi > i) {
                    val ref = s.substring(i + 1, semi)
                    val decoded = when {
                        ref.startsWith("#x") || ref.startsWith("#X") -> ref.substring(2).toIntOrNull(16)?.let { codePointToString(it) }
                        ref.startsWith("#") -> ref.substring(1).toIntOrNull()?.let { codePointToString(it) }
                        ref == "lt" -> "<"
                        ref == "gt" -> ">"
                        ref == "amp" -> "&"
                        ref == "quot" -> "\""
                        ref == "apos" -> "'"
                        else -> null
                    }
                    if (decoded != null) { out.append(decoded); i = semi + 1; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun codePointToString(cp: Int): String? = when {
        cp < 0 || cp > 0x10FFFF -> null
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v ushr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }
}
