package dev.roastbook.app.extraction

/** Bags shout. Names get title case, prose gets sentence case, but only when the input is ALL CAPS. */
object TextCase {
    private val keepUpper = setOf("USA", "UK", "SL28", "SL34", "SL14", "BW", "B&W", "PB", "AA", "AB", "DR")
    private val lowerWords = setOf("of", "the", "and", "de", "del", "la", "el", "y", "x", "×", "at", "in", "on", "for", "&")

    fun isShouting(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        return letters.length >= 3 && letters.all { it.isUpperCase() }
    }

    fun title(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().replace(Regex("\\s+"), " ")
        if (!isShouting(t)) return t
        return t.split(" ").mapIndexed { i, w ->
            when {
                w in keepUpper -> w
                i > 0 && w.lowercase() in lowerWords -> w.lowercase()
                else -> w.lowercase().replaceFirstChar { it.titlecase() }
                    .let { word -> word.split("-").joinToString("-") { p -> p.replaceFirstChar { it.titlecase() } } }
            }
        }.joinToString(" ")
    }

    fun sentence(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().replace(Regex("\\s+"), " ")
        if (!isShouting(t)) return t
        val lower = t.lowercase()
        val sb = StringBuilder(lower.length)
        var capNext = true
        for (ch in lower) {
            sb.append(if (capNext && ch.isLetter()) ch.titlecaseChar() else ch)
            if (ch.isLetter() || ch.isDigit()) capNext = false
            if (ch == '.' || ch == '!' || ch == '?') capNext = true
        }
        return sb.toString()
    }

    fun titleList(list: List<String>): List<String> = list.mapNotNull { title(it) }.filter { it.isNotBlank() }
}
