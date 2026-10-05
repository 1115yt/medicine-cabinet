package app.medicinecabinet.domain

/** 版本逐段按整数比较；本机允许历史版本，远端仅接受正式发布规则。 */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    init { require(major >= 0 && minor >= 0 && patch >= 0) }

    val versionName: String get() = "$major.$minor.$patch"

    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    companion object {
        private val versionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

        fun parseVersionName(value: String): ReleaseVersion? {
            if (value.length > 32) return null
            val match = versionPattern.matchEntire(value) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }

        fun parseStableTag(value: String): ReleaseVersion? {
            if (!value.startsWith('v')) return null
            return parseVersionName(value.drop(1))?.takeIf { it.major >= 1 && it.patch in 0..9 }
        }
    }
}
