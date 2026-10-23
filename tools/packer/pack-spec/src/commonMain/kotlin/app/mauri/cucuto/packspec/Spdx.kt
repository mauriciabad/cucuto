package app.mauri.cucuto.packspec

/** SPDX license expression check (SPDX spec annex D), case-insensitive for IDs and operators. */
object Spdx {
    private val TOKEN = Regex("\\(|\\)|[A-Za-z0-9.+:-]+")
    private val REF = Regex("^(?:DocumentRef-[A-Za-z0-9.-]+:)?LicenseRef-[A-Za-z0-9.-]+$")

    /** True if [expression] is a valid SPDX license expression made of known IDs or LicenseRef- references. */
    fun isValidExpression(expression: String): Boolean {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return false
        // Only spaces may separate tokens; any other character makes it free text.
        val tokens = mutableListOf<String>()
        var rest = trimmed
        while (rest.isNotEmpty()) {
            rest = rest.trimStart(' ')
            if (rest.isEmpty()) break
            val m = TOKEN.matchAt(rest, 0) ?: return false
            tokens += m.value
            rest = rest.substring(m.value.length)
        }
        val parser = Parser(tokens)
        return parser.expression() && parser.atEnd()
    }

    private class Parser(private val tokens: List<String>) {
        private var i = 0
        fun atEnd() = i == tokens.size
        private fun peek() = tokens.getOrNull(i)
        private fun isOp(t: String?, op: String) = t != null && t.equals(op, ignoreCase = true)

        fun expression(): Boolean {
            if (!term()) return false
            while (isOp(peek(), "AND") || isOp(peek(), "OR")) {
                i++
                if (!term()) return false
            }
            return true
        }

        private fun term(): Boolean {
            val t = peek() ?: return false
            if (t == "(") {
                i++
                if (!expression() || peek() != ")") return false
                i++
                return true
            }
            if (!license(t)) return false
            i++
            if (isOp(peek(), "WITH")) {
                i++
                val ex = peek() ?: return false
                if (ex.lowercase() !in SpdxIds.exceptions && !ex.startsWith("AdditionRef-")) return false
                i++
            }
            return true
        }

        private fun license(t: String): Boolean {
            if (isOp(t, "AND") || isOp(t, "OR") || isOp(t, "WITH") || t == ")") return false
            if (REF.matches(t)) return true
            return t.removeSuffix("+").lowercase() in SpdxIds.licenses
        }
    }
}
