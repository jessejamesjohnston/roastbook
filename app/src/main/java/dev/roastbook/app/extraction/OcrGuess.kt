package dev.roastbook.app.extraction

import kotlin.math.roundToInt

/**
 * The last tier: a rule-based guess over the recognized text, for a phone with no working model
 * and no cloud key. It never sees the photos, so it only fills what text rules can find with
 * reasonable confidence, and the card labels the result as a guess:
 *
 * - roaster: a line that names itself a roaster ("... Coffee Roasters", "... Roastery"), else a
 *   short brand-looking line at the top of the front photo, preferring one that matches a web
 *   address printed on the bag
 * - origin country: a known coffee-growing country (the most mentioned; ties go to the first)
 * - process: a labeled "Process:" line, else washed / natural / honey / anaerobic keywords
 * - elevation: "1,800 masl", "1800m", "1,700-1,900 m" (midpoint), "6,000 ft" (converted)
 * - varietals: a list of known cultivar names
 * - tasting notes: only from an explicit notes line ("Tasting notes: ..."), never from taglines
 * - region / producer: only from labeled lines
 * - roast level and net weight: keyword and unit patterns
 *
 * The coffee's name is never guessed: without the photos, names and taglines look alike.
 */
object OcrGuess {
    private val HEADER = Regex("""^---\s*photo\s+\d+\s*\(([a-z]+)\)\s*---$""", RegexOption.IGNORE_CASE)

    /** A page of recognized text, rebuilt from the stored OCR text when only that is at hand. */
    data class Page(val role: String, val lines: List<String>) {
        val isSeller: Boolean get() = role == "seller"
    }

    fun guess(pages: List<OcrPage>): BagFacts =
        guessPages(pages.map { p -> Page(if (p.isSeller) "seller" else p.label.substringAfter('(', "").substringBefore(')'), lines(p.text)) })

    /** From the stored `--- photo N (role) ---` text (the scheduler's failure path has only this). */
    fun guess(storedOcrText: String?): BagFacts {
        if (storedOcrText.isNullOrBlank()) return BagFacts()
        val pages = mutableListOf<Page>()
        var role = "front"
        var current = mutableListOf<String>()
        for (raw in storedOcrText.lines()) {
            val t = raw.trim()
            val m = HEADER.find(t)
            if (m != null) {
                if (current.isNotEmpty()) pages += Page(role, current)
                role = m.groupValues[1].lowercase(); current = mutableListOf()
            } else if (t.isNotEmpty()) current += t
        }
        if (current.isNotEmpty()) pages += Page(role, current)
        return guessPages(pages)
    }

    /** The number of fields a guess filled, for the trail and the card. */
    fun count(f: BagFacts): Int = listOf(f.roaster, f.originCountry, f.region, f.producer, f.process, f.roastLevel).count { it != null } +
        listOf(f.elevationM, f.bagSizeG).count { it != null } + (if (f.varietal.isNotEmpty()) 1 else 0) + (if (f.tastingNotes.isNotEmpty()) 1 else 0)

    private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !HEADER.matches(it) }

    private fun guessPages(pages: List<Page>): BagFacts {
        // The seller's screenshot describes the coffee too, but its brand lines are the seller's.
        val bag = pages.filter { !it.isSeller }
        val all = pages.flatMap { it.lines }
        val text = all.joinToString("\n")
        return BagFacts(
            roaster = roaster(bag.ifEmpty { pages }, text),
            originCountry = labeled(all, ORIGIN_LABELS)?.let(::country) ?: country(text),
            region = labeled(all, REGION_LABELS)?.let(::cleanValue),
            producer = labeled(all, PRODUCER_LABELS)?.let(::cleanValue),
            process = process(all),
            varietal = labeled(all, VARIETAL_LABELS)?.let(::varietals)?.takeIf { it.isNotEmpty() } ?: varietals(text),
            elevationM = elevation(all),
            roastLevel = roastLevel(text),
            bagSizeG = bagSize(text),
            tastingNotes = tastingNotes(all),
        )
    }

    // ---- labeled lines ----

    private val ORIGIN_LABELS = listOf("origin", "country", "country of origin")
    private val REGION_LABELS = listOf("region", "area", "municipality", "department", "state", "zone")
    private val PRODUCER_LABELS = listOf(
        "producer", "producers", "farm", "farmer", "grower", "growers", "estate", "cooperative", "co-op",
        "washing station", "station", "finca",
    )
    private val PROCESS_LABELS = listOf("process", "processing", "processed", "method")
    private val VARIETAL_LABELS = listOf("varietal", "varietals", "variety", "varieties", "cultivar", "cultivars")
    private val ALTITUDE_LABELS = listOf("altitude", "elevation", "masl", "grown at")
    private val NOTES_LABELS = listOf(
        "tasting notes", "taste notes", "flavor notes", "flavour notes", "cup notes", "cupping notes", "notes",
        "tastes like", "we taste", "in the cup", "flavor", "flavour", "flavors", "flavours", "profile",
    )

    /**
     * The value after a label: "Label: value", "Label - value", a label alone on its line (value on
     * the next line), or an ALL-CAPS label followed by its value ("PROCESS WASHED"), which bags print
     * without punctuation. [requireSeparator] turns the last form off for labels that also start
     * ordinary sentences ("Notes of cherry..." is prose, not a notes line).
     */
    internal fun labeled(lines: List<String>, labels: List<String>, requireSeparator: Boolean = false): String? {
        val sorted = labels.sortedByDescending { it.length }
        for ((i, line) in lines.withIndex()) {
            for (label in sorted) {
                if (!line.startsWith(label, ignoreCase = true)) continue
                val after = line.substring(label.length)
                if (after.isNotEmpty() && after[0].isLetterOrDigit()) continue
                val t = after.trim()
                when {
                    t.isEmpty() -> return lines.getOrNull(i + 1)
                    t[0] in ":-–|" -> return t.drop(1).trim().ifEmpty { lines.getOrNull(i + 1) }
                    !requireSeparator && line.take(label.length).none { it.isLowerCase() } -> return t
                }
            }
        }
        return null
    }

    private fun cleanValue(v: String): String? = v.trim().trim(',', '.', ';', ':').takeIf { it.length in 2..60 }?.let { TextCase.title(it) }

    // ---- roaster ----

    private val ROASTER_WORDS = Regex(
        """\b(roasters?|roastery|roasting\s+(co\.?|company)|coffee\s+(co\.?|company|roasters?)|kaffeeröst\w*|torrefaz\w*)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_BRAND = Regex(
        """\b(whole\s*bean|ground|net\s*wt|net\s*weight|roasted\s*on|roast\s*date|best\s*by|light|medium|dark|roast|single\s*origin|blend|""" +
            """specialty|fresh|tasting|notes|process|washed|natural|honey|origin|varietal|variety|altitude|elevation|masl|""" +
            """organic|fair\s*trade|decaf|espresso|filter|oz|lbs?|grams?|www|http|\.com)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun roaster(pages: List<Page>, text: String): String? {
        val domains = DOMAIN.findAll(text).map { it.groupValues[1].lowercase().replace("-", "") }.toList()
        fun inDomain(name: String): Boolean {
            val letters = name.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }
            val core = letters.replace(Regex("(coffeeroasters|roasters|roastery|roastingcompany|roastingco|coffeecompany|coffeeco|coffee)$"), "")
            return core.length >= 3 && domains.any { it.contains(core) }
        }
        // 1) A line that says it is a roaster.
        for (page in pages) {
            for ((i, line) in page.lines.withIndex()) {
                if (!ROASTER_WORDS.containsMatchIn(line) || line.length > 48 || line.contains(':') || line.count { it.isLetter() } < 5) continue
                // Bags often break "LARKMOOR / COFFEE ROASTERS" over two lines: join a bare suffix to the brand above it.
                val bare = ROASTER_WORDS.replace(line, "").replace(Regex("(?i)\\b(coffee|the)\\b"), "").count { it.isLetter() } < 3
                val name = if (bare) page.lines.getOrNull(i - 1)?.takeIf { brandLike(it) }?.let { "$it $line" } ?: continue else line
                return TextCase.title(clean(name))
            }
        }
        // 2) A short, brand-looking line near the top of the first bag photo (bags print the brand first).
        val front = pages.firstOrNull { it.role == "front" } ?: pages.firstOrNull() ?: return null
        val candidates = front.lines.take(4).filter { brandLike(it) }
        // Prefer a line the bag's web address confirms; otherwise only the very first line qualifies.
        val best = candidates.firstOrNull { inDomain(it) } ?: front.lines.firstOrNull()?.takeIf { it in candidates }
        return best?.let { TextCase.title(clean(it)) }
    }

    private fun clean(s: String) = s.replace(Regex("[®™©]"), "").trim().trimEnd('.', ',').replace(Regex("\\s+"), " ")

    private val DOMAIN = Regex(
        """\b(?:www\.)?([a-z0-9-]{3,})\.(?:com|co|coffee|net|org|shop|store|cafe|ca|co\.uk|com\.au|de|jp|io)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun brandLike(line: String): Boolean {
        if (DOMAIN.containsMatchIn(line) || line.contains('@')) return false
        val words = line.trim().split(Regex("\\s+"))
        if (words.size > 4 || line.length > 32) return false
        val letters = line.count { it.isLetter() }
        if (letters < 3 || letters < line.count { !it.isWhitespace() } * 0.7) return false
        // "COFFEE" alone is not a brand; "LARKMOOR COFFEE" is.
        if (line.replace(Regex("""(?i)\bcoffee\b"""), "").count { it.isLetter() } < 3) return false
        if (NOT_BRAND.containsMatchIn(line)) return false
        if (country(line) != null || varietals(line).isNotEmpty()) return false
        return true
    }

    // ---- origin ----

    /** Coffee-growing countries and the spellings bags use; value = the name stored. */
    private val COUNTRIES: List<Pair<Regex, String>> = listOf(
        "ethiopia" to "Ethiopia", "kenya" to "Kenya", "colombia" to "Colombia", "brazil|brasil" to "Brazil",
        "guatemala" to "Guatemala", "honduras" to "Honduras", "costa\\s+rica" to "Costa Rica", "el\\s+salvador" to "El Salvador",
        "nicaragua" to "Nicaragua", "panam[aá]" to "Panama", "mexico|méxico" to "Mexico", "per[uú]" to "Peru", "bolivia" to "Bolivia",
        "ecuador" to "Ecuador", "rwanda" to "Rwanda", "burundi" to "Burundi", "tanzania" to "Tanzania", "uganda" to "Uganda",
        "(?:dr\\s+)?congo|drc" to "DR Congo", "yemen" to "Yemen", "indonesia|sumatra|java|sulawesi|flores|bali" to "Indonesia",
        "papua\\s+new\\s+guinea|png" to "Papua New Guinea", "india" to "India", "vietnam|viet\\s+nam" to "Vietnam",
        "china|yunnan" to "China", "myanmar" to "Myanmar", "thailand" to "Thailand", "laos" to "Laos",
        "timor[-\\s]leste|east\\s+timor" to "Timor-Leste", "philippines" to "Philippines", "jamaica" to "Jamaica", "haiti" to "Haiti",
        "dominican\\s+republic" to "Dominican Republic", "puerto\\s+rico" to "Puerto Rico", "hawai[i']i?|kona" to "United States",
        "malawi" to "Malawi", "zambia" to "Zambia", "zimbabwe" to "Zimbabwe", "taiwan" to "Taiwan", "nepal" to "Nepal",
        "venezuela" to "Venezuela", "cuba" to "Cuba", "cameroon" to "Cameroon", "madagascar" to "Madagascar",
    ).map { (p, name) -> Regex("""\b(?:$p)\b""", RegexOption.IGNORE_CASE) to name }

    internal fun country(raw: String): String? {
        // Bags print "PERÚ", "MÉXICO", "PANAMÁ": compare without accents.
        val text = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val hits = COUNTRIES.mapNotNull { (re, name) ->
            val ms = re.findAll(text).toList()
            if (ms.isEmpty()) null else Triple(name, ms.size, ms.first().range.first)
        }
        return hits.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third }).firstOrNull()?.first
    }

    // ---- process ----

    private val PROCESSES: List<Pair<Regex, String>> = listOf(
        "carbonic\\s+maceration" to "Carbonic maceration",
        "anaerobic\\s+natural|natural\\s+anaerobic" to "Anaerobic natural",
        "anaerobic\\s+washed|washed\\s+anaerobic" to "Anaerobic washed",
        "anaerobic\\s+honey" to "Anaerobic honey",
        "anaerobic" to "Anaerobic",
        "double\\s+fermentation|double\\s+fermented" to "Double fermentation",
        "thermal\\s+shock" to "Thermal shock",
        "wet[-\\s]hulled|giling\\s+basah" to "Wet-hulled",
        "semi[-\\s]washed" to "Semi-washed",
        "pulped\\s+natural" to "Pulped natural",
        "(?:white|yellow|red|black)\\s+honey" to "",
        "honey\\s+process(?:ed)?" to "Honey",
        "fully\\s+washed|washed|wet[-\\s]process(?:ed)?" to "Washed",
        "natural(?:\\s+process(?:ed)?)?|dry[-\\s]process(?:ed)?|sun[-\\s]dried" to "Natural",
    ).map { (p, name) -> Regex("""\b(?:$p)\b""", RegexOption.IGNORE_CASE) to name }

    internal fun process(lines: List<String>): String? {
        labeled(lines, PROCESS_LABELS)?.let { v ->
            matchProcess(v)?.let { return it }
            // A labeled bare "Honey" is a process; unlabeled, it is more often a tasting note.
            if (Regex("""^honey\b""", RegexOption.IGNORE_CASE).containsMatchIn(v)) return "Honey"
        }
        // Unlabeled: short spec-like lines only, and never the tasting-notes line.
        val notesLine = Regex("""^(${NOTES_LABELS.joinToString("|") { Regex.escape(it) }})\b""", RegexOption.IGNORE_CASE)
        val text = lines.filter { it.length <= 40 && !notesLine.containsMatchIn(it) }.joinToString("\n")
        return matchProcess(text)
    }

    private fun matchProcess(v: String): String? {
        for ((re, name) in PROCESSES) {
            val m = re.find(v) ?: continue
            return name.ifEmpty { TextCase.sentence(m.value.lowercase().replaceFirstChar { it.uppercase() }) }
        }
        return null
    }

    // ---- varietals ----

    private val VARIETALS: List<Pair<Regex, String>> = listOf(
        "pink\\s+bourbon" to "Pink Bourbon", "red\\s+bourbon" to "Red Bourbon", "yellow\\s+bourbon" to "Yellow Bourbon",
        "bourbon\\s+pointu|laurina" to "Laurina", "bourbon" to "Bourbon",
        "gesha|geisha" to "Gesha", "typica" to "Typica", "caturra" to "Caturra", "yellow\\s+catua[ií]" to "Yellow Catuai",
        "red\\s+catua[ií]" to "Red Catuai", "catua[ií]" to "Catuai", "castillo" to "Castillo", "pacamara" to "Pacamara",
        "pacas" to "Pacas", "sl[-\\s]?28" to "SL28", "sl[-\\s]?34" to "SL34", "sl[-\\s]?14" to "SL14", "ruiru\\s*11" to "Ruiru 11",
        "batian" to "Batian", "(?:ethiopian\\s+)?heirloom" to "Heirloom", "landrace" to "Landrace", "74110" to "74110",
        "74112" to "74112", "74158" to "74158", "maragogype|maragogipe" to "Maragogype", "mundo\\s+novo" to "Mundo Novo",
        "catimor" to "Catimor", "sarchimor" to "Sarchimor", "villa\\s+sarchi" to "Villa Sarchi", "sidra" to "Sidra",
        "wush\\s+wush" to "Wush Wush", "tabi" to "Tabi", "mokka|mocha\\s+variety" to "Mokka", "obat[aã]" to "Obata",
        "marsellesa" to "Marsellesa", "parainema" to "Parainema", "lempira" to "Lempira", "ihcafe\\s*90|ih\\s*90" to "IHCAFE 90",
        "icatu" to "Icatu", "chiroso" to "Chiroso", "rume\\s+sudan" to "Rume Sudan", "kent" to "Kent", "s795|s\\.795" to "S795",
        "tekisic" to "Tekisic", "java\\s+variety" to "Java", "arara" to "Arara", "topazio|topázio" to "Topazio",
        "catigua|catiguá" to "Catigua", "caturra\\s+chiroso" to "Chiroso", "cenicaf[eé]\\s*1" to "Cenicafe 1", "tabi" to "Tabi",
    ).map { (p, name) -> Regex("""\b(?:$p)\b""", RegexOption.IGNORE_CASE) to name }

    internal fun varietals(text: String): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        val taken = mutableListOf<IntRange>()
        for ((re, name) in VARIETALS) {
            for (m in re.findAll(text)) {
                if (taken.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                taken += m.range; found += m.range.first to name
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct().take(6)
    }

    // ---- elevation ----

    private val NUM = """\d{1,2}[,.]?\d{3}|\d{3,4}"""
    private val ELEVATION = Regex(
        """($NUM)\s*(?:(?:-|–|—|to)\s*($NUM))?\s*(m\.?\s*a\.?\s*s\.?\s*l\.?|masl|meters?|metres?|m\b|ft\.?|feet)""",
        RegexOption.IGNORE_CASE,
    )

    internal fun elevation(lines: List<String>): Int? {
        fun num(s: String) = s.replace(",", "").replace(".", "").toIntOrNull()
        val text = lines.joinToString("\n")
        for (m in ELEVATION.findAll(text)) {
            val lo = num(m.groupValues[1]) ?: continue
            val hi = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::num)
            val feet = m.groupValues[3].lowercase().startsWith("f")
            var v = if (hi != null && hi > lo) (lo + hi) / 2.0 else lo.toDouble()
            if (feet) v *= 0.3048
            val r = v.roundToInt()
            if (r in 300..3000) return r
        }
        // "Altitude: 1850" with no unit.
        labeled(lines, ALTITUDE_LABELS)?.let { v ->
            val nums = Regex(NUM).findAll(v).mapNotNull { num(it.value) }.toList()
            val r = if (nums.size >= 2 && nums[1] > nums[0]) (nums[0] + nums[1]) / 2 else nums.firstOrNull()
            if (r != null && r in 300..3000) return r
        }
        return null
    }

    // ---- roast level and weight ----

    private val ROAST = Regex(
        """\b(extra[-\s]light|light[-\s]medium|medium[-\s]light|medium[-\s]dark|light|medium|dark|omni)""" +
            """(?:\s*[-\s]?\s*roast(?:ed)?|\s+roast\s+level)\b""",
        RegexOption.IGNORE_CASE,
    )

    internal fun roastLevel(text: String): String? =
        ROAST.find(text)?.groupValues?.get(1)?.lowercase()?.replace(Regex("\\s+"), "-")?.replaceFirstChar { it.uppercase() }

    internal fun bagSize(text: String): Int? {
        Regex("""(\d{2,4}(?:[.,]\d)?)\s*(?:g|gr|grams?)\b""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val g = m.groupValues[1].replace(',', '.').toDoubleOrNull()?.roundToInt()
            if (g != null && g in 50..5000) return g
        }
        Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s*(?:oz|ounces?)\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val oz = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val g = (oz * 28.35).roundToInt()
            if (g in 50..5000) return g
        }
        Regex("""(\d(?:[.,]\d{1,2})?)\s*(?:kg|kilograms?)\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val g = ((m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let) * 1000).roundToInt()
            if (g in 50..5000) return g
        }
        Regex("""(\d(?:[.,]\d{1,2})?)\s*(?:lbs?|pounds?)\b""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val g = ((m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let) * 453.6).roundToInt()
            if (g in 50..5000) return g
        }
        return null
    }

    // ---- tasting notes ----

    internal fun tastingNotes(lines: List<String>): List<String> {
        var v = labeled(lines, NOTES_LABELS, requireSeparator = true) ?: return emptyList()
        // A list that ends in a comma continues on the next line ("blackcurrant, grapefruit," / "cane sugar").
        var at = lines.indexOfFirst { it.endsWith(v) }
        while (v.trimEnd().endsWith(",") && at >= 0 && at + 1 < lines.size && lines[at + 1].length <= 40) { at++; v = v.trimEnd() + " " + lines[at] }
        return v.replace(Regex("""^(of|with)\s+""", RegexOption.IGNORE_CASE), "")
            .split(Regex("""\s*(?:,|·|•|/|\||;|\+|&|\band\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim().trim('.', '!', '"', '\'') }
            .filter { it.length in 3..30 && it.count { c -> c.isLetter() } >= 3 && it.split(' ').size <= 4 }
            .map { n -> TextCase.title(n)?.takeIf { it != n } ?: n.split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercaseChar) } }
            .distinct()
            .take(6)
    }
}
