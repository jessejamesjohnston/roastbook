package dev.roastbook.app.update

/**
 * Release versions as Roastbook names them: `0.3.5`, optionally tagged `v0.3.5`. Compared number by
 * number, so 0.10.0 is newer than 0.9.0 and 1.0 equals 1.0.0. Anything after the numbers (a
 * pre-release suffix such as `-beta1`) is ignored for ordering.
 */
object AppVersion {
    private val SHAPE = Regex("""^[vV]?(\d+(?:\.\d+)*)(?:[-+][0-9A-Za-z.-]*)?$""")

    /** The numeric parts of [text], or null when it is not a version at all. */
    fun parse(text: String?): List<Int>? {
        val m = SHAPE.matchEntire(text?.trim() ?: return null) ?: return null
        return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    /** Whether [candidate] is a later version than [current]; null when either cannot be read. */
    fun isNewer(candidate: String?, current: String?): Boolean? {
        val a = parse(candidate) ?: return null
        val b = parse(current) ?: return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** [text] without its leading `v`, for display ("Roastbook 0.4.0 is available"). */
    fun display(text: String): String = text.trim().removePrefix("v").removePrefix("V")
}
