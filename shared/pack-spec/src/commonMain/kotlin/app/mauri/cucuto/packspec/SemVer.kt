package app.mauri.cucuto.packspec

/** SemVer 2.0.0 precedence for the `version` fields (build metadata is not allowed by the schema). */
data class SemVer(val major: Long, val minor: Long, val patch: Long, val preRelease: List<String>) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A version without pre-release identifiers ranks above any pre-release of the same core version.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val PATTERN = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")

        fun parse(text: String): SemVer? {
            val m = PATTERN.matchEntire(text) ?: return null
            val (a, b, c, pre) = m.destructured
            return SemVer(a.toLongOrNull() ?: return null, b.toLongOrNull() ?: return null, c.toLongOrNull() ?: return null, if (pre.isEmpty()) emptyList() else pre.split('.'))
        }
    }
}
