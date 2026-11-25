package dev.roastbook.app.extraction

/**
 * The last word on a reading's text before it is saved, whichever tier produced it (the built-in
 * AI, the on-device reader, a Gemini key, or the guess from the label text). A model that slips
 * out of its JSON leaves residue a person should never see as a coffee's details:
 *
 * - A value that is only placeholder words (`null`, `none`, `n/a`, `unknown`, `undefined`, in any
 *   case, alone or repeated, with or without quotes and punctuation) is empty: "null null" is not
 *   a coffee's name.
 * - Quotes, commas, brackets and braces left at either end by a broken answer are trimmed. A quote,
 *   bracket or brace goes only when it has no partner in the value, so text that was quoted or
 *   bracketed on purpose keeps its marks, and apostrophes and periods are never touched.
 * - A roast date that is not recognizably a date is empty (see [date]).
 *
 * Every rule only removes; nothing is invented or reworded. Running it twice changes nothing.
 */
object FieldCleanup {
    fun clean(f: BagFacts): BagFacts = f.copy(
        roaster = text(f.roaster),
        coffeeName = text(f.coffeeName),
        originCountry = text(f.originCountry),
        region = text(f.region),
        producer = text(f.producer),
        process = text(f.process),
        varietal = list(f.varietal),
        roastLevel = text(f.roastLevel),
        roastDate = date(f.roastDate),
        tastingNotes = list(f.tastingNotes),
        bagDescription = text(f.bagDescription),
        sellerDescription = text(f.sellerDescription),
    )

    private val PLACEHOLDERS = setOf("null", "none", "n/a", "unknown", "undefined")
    /** What separates words when checking for placeholders: space, quotes, punctuation, brackets, dashes. */
    private val SEPARATORS = Regex("""[\s"'`,;:.!?\[\]{}()\-–—]+""")

    /** One text value, trimmed of residue; null when nothing real is left. */
    fun text(s: String?): String? {
        if (s == null) return null
        val t = trimResidue(s.trim())
        if (t.isEmpty()) return null
        val words = t.lowercase().split(SEPARATORS).filter { it.isNotEmpty() && it.any { c -> c != '/' } }
        if (words.all { it in PLACEHOLDERS }) return null
        return t
    }

    private fun list(items: List<String>): List<String> = items.mapNotNull { text(it) }.distinct()

    /** Strips JSON residue from both ends until none is left (see the class comment). */
    private fun trimResidue(input: String): String {
        var t = input
        while (true) {
            val before = t
            t = t.trim().trim(',').trim()
            if (t.endsWith('"') && t.count { it == '"' } % 2 == 1) t = t.dropLast(1)
            if (t.startsWith('"') && t.count { it == '"' } % 2 == 1) t = t.drop(1)
            for ((open, close) in listOf('[' to ']', '{' to '}')) {
                if (t.endsWith(close) && t.count { it == close } > t.count { it == open }) t = t.dropLast(1)
                if (t.startsWith(open) && t.count { it == open } > t.count { it == close }) t = t.drop(1)
            }
            if (t == before) return t
        }
    }

    private const val MONTH =
        "jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sep(t|tember)?|oct(ober)?|nov(ember)?|dec(ember)?"
    /** A year first: 2026-11-03, 2026/11/3. */
    private val YEAR_FIRST = Regex("""(?<!\d)(19|20)\d{2}[./-](\d{1,2})[./-](\d{1,2})(?!\d)""")
    /** Day and month in either order, with an optional year: 03/11, 3.11.26, 11-03-2026. */
    private val DAY_MONTH = Regex("""(?<!\d)(\d{1,2})[./-](\d{1,2})(?:[./-](\d{2}|\d{4}))?(?![\d./-])""")
    /** A month by name beside a day or a year: Nov 3, 3rd November, November 2026. */
    private val NAMED_MONTH = Regex(
        """\b($MONTH)\.?,?\s+\d{1,4}\b|\b\d{1,2}(st|nd|rd|th)?\s+(of\s+)?($MONTH)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A roast date as the bag prints it, kept word for word, or null when it is not recognizably a
     * date: a year-first date, a day and month in either order (a year is optional, since many bags
     * print only the day and month), or a month by name beside a day or a year. Text that is none of
     * these, such as a lot code or placeholder words, is not a date.
     */
    fun date(s: String?): String? {
        val t = text(s) ?: return null
        if (YEAR_FIRST.find(t)?.let { m -> m.groupValues[2].toInt() in 1..12 && m.groupValues[3].toInt() in 1..31 } == true) return t
        if (DAY_MONTH.findAll(t).any { m -> plausibleDayMonth(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }) return t
        if (NAMED_MONTH.containsMatchIn(t)) return t
        return null
    }

    private fun plausibleDayMonth(a: Int, b: Int) = a in 1..31 && b in 1..31 && (a <= 12 || b <= 12)
}
