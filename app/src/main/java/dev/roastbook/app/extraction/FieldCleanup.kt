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
 * - A roast date with no digit at all is empty; any other date is kept as written (see [date]).
 * - A tasting note that only repeats the process is dropped when the process is more than one word
 *   ("Lychee carbonic maceration" is how the coffee was made, not a flavor). A one-word process stays
 *   as a note too: "Honey" can be both.
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
        tastingNotes = list(f.tastingNotes).filterNot { repeatsProcess(it, text(f.process)) },
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

    private val SPACES = Regex("""\s+""")

    /** True when [note] is [process] again, ignoring case and spacing, and the process is more than one word. */
    private fun repeatsProcess(note: String, process: String?): Boolean {
        if (process == null) return false
        fun words(s: String) = s.trim().lowercase().split(SPACES)
        return words(process).size > 1 && words(note) == words(process)
    }

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

    /**
     * A roast date as the bag prints it, trimmed of residue and otherwise kept word for word. Bags
     * stamp it in every style ("03NOV26", "11.03.26", "3 nov. 2026", "KW 45"), and nothing in the app
     * parses it (it is shown and edited as text), so the only dates dropped are junk: what [text]
     * empties, and text with no digit at all, which can't name a day.
     */
    fun date(s: String?): String? = text(s)?.takeIf { t -> t.any { it.isDigit() } }
}
