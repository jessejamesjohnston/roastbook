package dev.roastbook.app.extraction

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The net weight a bag's label text states, read as one quantity, and what it may say about a
 * model's bag size.
 *
 * **The design rule** (each part has a test that fails when it is broken):
 * 1. A bag size the person typed is never changed. (Enforced where the reading is saved, in
 *    [Extractor]: this object only ever sees a model's figure.)
 * 2. A model's figure inside [BAND] is replaced only when the label prints two units that agree
 *    with each other and disagree with the model ("12 oz (340 g)", "1 lb (16 oz)"). One printed unit
 *    replaces only a figure outside the band.
 * 3. A weight is guessed for a blank only from a reading with no other way to read it.
 * 4. Anything ambiguous leaves the bag size as it is.
 *
 * **How a line is read:**
 * - An amount is a whole number, a decimal ("1.5", "0,5"), a fraction ("1/2", "½") or a mixed number
 *   ("1 1/2"), followed by a real unit token (g, kg, oz, lb and their spelled forms) that ends at a
 *   word boundary. On a "net" line, "0Z" after a space is also ounces (a common misread of "OZ").
 *   Nothing without a unit is read, so lot codes, prices, dates and roast numbers are not.
 * - A mark followed by three digits may be a thousands mark ("2,268 g") or a decimal point ("2.268
 *   kg"); only a reading inside the band counts, and two that both do are refused.
 * - Inside an amount, a letter that is only ever a misread digit is read as that digit: O and o as
 *   0, I and l as 1, S as 5, B as 8, Z as 2. A digit read as another digit ("70 oz" for "10 oz")
 *   can't be seen on one unit, which is why one unit never replaces a plausible figure.
 * - Pounds followed by ounces under a pound ("1 LB 4 OZ") are one weight. Two printings of one
 *   weight ("1 LB (16 OZ)", "16 OZ (1 LB)", "12 oz (340 g)") must agree within the rounding of the
 *   more precise one, and the weight is the more precise printing.
 * - A multipack ("2 x 250 g"), a range ("250-300 g") or an amount split by a space ("2 500 g")
 *   makes the line untrustworthy.
 */
object NetWeight {
    /** Grams a retail coffee bag can hold: from a 1 oz sample to a 2.5 kg bag. */
    val BAND = 25..2500

    /** The smallest weight read from a line that doesn't say "net" (a 2 oz bag is 57 g). */
    private const val OFF_NET_FLOOR = 50

    /** Words of a recipe, a serving, a dose, a scoop or a "per" amount: such a line never states the bag's weight. */
    private val NOT_THE_BAG = Regex(
        """\b(?:per|serving|servings|dose|doses|scoop|scoops|sachet|sachets|recipe|brew|brewing|use|pour|ratio|each|cup|cups|portion|water)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** How close the coarser of two printings must be to confirm the other (2.2 lb for 1 kg is 2.3%). */
    private const val CONFIRMS = 0.03

    /** A model's figure this close to the label's is the same weight (rounded conversions). */
    private const val SAME = 0.05

    private const val GRAMS_PER_OZ = 28.35
    private const val GRAMS_PER_LB = 453.6

    /** What one line of label text states. */
    private sealed interface LineReading {
        /** One quantity, in grams; [dual] when it was printed twice, in two units that agree. */
        data class Weight(val grams: Int, val dual: Boolean) : LineReading
        /** The line names a weight but not one that can be trusted: several, disagreeing, or out of the band. */
        data object Refused : LineReading
    }

    /** A weight the label states, from [printed]. */
    data class Printed(val grams: Int, val dual: Boolean)

    private enum class Unit(val grams: Double, val metric: Boolean) { G(1.0, true), KG(1000.0, true), OZ(GRAMS_PER_OZ, false), LB(GRAMS_PER_LB, false) }

    // Letters that stand only for one digit when text recognition misreads it.
    private const val DIGITISH = "0-9OoIlSBZ"
    private val QUANTITY = Regex(
        "(?<![\\w.,/])" +
            "(" +
            "[$DIGITISH]{1,4}(?:[.,][$DIGITISH]{1,3})?(?:\\s+\\d{1,2}/\\d{1,2}|\\s*[½¼¾])?" +
            "|\\d{1,2}/\\d{1,2}|[½¼¾]" +
            ")" +
            "(?:\\s*(?i:(kilograms?|kilos?|kgs?|grams?|gr|g|ounces?|oz|pounds?|lbs?))|\\s+(0[zZ]))(?![\\p{L}\\p{N}])",
    )
    /** A line that states the net weight: "net", "nett", "net wt", and recognition's "NT WT" for "NET WT". */
    private val NET_LINE = Regex("""\b(?:nett?|net\s*wt|netwt|nt\.?\s*wt)\b""", RegexOption.IGNORE_CASE)
    /** What may sit between the two halves of a dual declaration or a pounds-and-ounces weight. */
    private val JOINER = Regex("""[\s()\[\]/|·,&+\-.]*(?:(?:and|e|℮)(?![\p{L}\p{N}])\s*)?[\s()\[\]/|·,&+\-.]*""", RegexOption.IGNORE_CASE)
    /** Just before an amount: a multipack's "x", a range's dash, or a digit group split off by a space ("2 500 g"). */
    private val BEFORE_UNTRUSTED = Regex("""(?:(?:^|\s)[x×*]\s*|\d\s*[-–]\s*|\d )$""")
    /** Just after a unit: a multipack's "x 2". */
    private val AFTER_UNTRUSTED = Regex("""^[\s)\]]*[x×*]\s*\d""")

    private fun unitOf(token: String): Unit = when (token.lowercase()) {
        "g", "gr", "gram", "grams" -> Unit.G
        "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms" -> Unit.KG
        "oz", "ounce", "ounces" -> Unit.OZ
        else -> Unit.LB
    }

    private fun digits(raw: String): String = raw.map {
        when (it) { 'O', 'o' -> '0'; 'I', 'l' -> '1'; 'S' -> '5'; 'B' -> '8'; 'Z' -> '2'; else -> it }
    }.joinToString("")

    /** Every way [raw] can be read as a number; a decimal mark followed by three digits is read both ways. */
    private fun amounts(raw: String): Set<Double> {
        if (raw.none { it.isDigit() } && raw.none { it in "½¼¾" }) return emptySet()
        val t = raw.trim()
        fun fraction(f: String): Double? = when (f) {
            "½" -> 0.5; "¼" -> 0.25; "¾" -> 0.75
            else -> f.split('/').takeIf { it.size == 2 }?.let { (a, b) ->
                val d = b.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
                a.toDoubleOrNull()?.div(d)
            }
        }
        // A fraction alone, or a whole number followed by one.
        Regex("""^(?:(\S+?)\s*)?(\d{1,2}/\d{1,2}|[½¼¾])$""").find(t)?.let { m ->
            val whole = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { digits(it).toDoubleOrNull() ?: return emptySet() } ?: 0.0
            val part = fraction(m.groupValues[2]) ?: return emptySet()
            return setOf(whole + part)
        }
        val d = digits(t)
        val sep = d.indexOfFirst { it == '.' || it == ',' }
        if (sep < 0) return setOfNotNull(d.toDoubleOrNull())
        val (head, tail) = d.substring(0, sep) to d.substring(sep + 1)
        val asDecimal = "$head.$tail".toDoubleOrNull()
        val asThousands = if (tail.length == 3) "$head$tail".toDoubleOrNull() else null
        return setOfNotNull(asDecimal, asThousands)
    }

    /** [slack]: how far the printed amount may sit from the exact weight, from its decimal places ("2.2 lb" is within 0.05 lb). */
    private data class Quantity(
        val grams: Set<Int>, val metric: Boolean, val unit: Unit, val start: Int, val end: Int, val slack: Double = 0.0,
        /** Printed twice, in two units that agree. */
        val restated: Boolean = false,
    )

    /**
     * Every quantity on [line]. One whose amount can't be read at all ("I LB"), or that is part of a
     * multipack, a range or a split number, has no readings, which refuses the line.
     */
    private fun quantities(line: String): List<Quantity> = QUANTITY.findAll(line).filter { m ->
        // "0Z" counts as ounces only after a line's net words ("NET WT 12 0Z"), never in a code that
        // merely shares a line with "net" ("Lot 12 0Z net").
        m.groupValues[3].isEmpty() || NET_LINE.find(line)?.let { it.range.first < m.range.first } == true
    }.map { m ->
        val unit = unitOf(m.groupValues[2].ifEmpty { "oz" })
        val untrusted = BEFORE_UNTRUSTED.containsMatchIn(line.substring(0, m.range.first)) ||
            AFTER_UNTRUSTED.containsMatchIn(line.substring(m.range.last + 1))
        val grams = if (untrusted) emptySet() else amounts(m.groupValues[1]).map { (it * unit.grams).roundToInt() }.toSet()
        val decimals = Regex("[.,](\\w{1,2})$").find(m.groupValues[1].trim())?.groupValues?.get(1)?.length ?: 0
        val slack = if (decimals == 0) 0.0 else 0.5 * Math.pow(10.0, -decimals.toDouble()) * unit.grams
        Quantity(grams, unit.metric, unit, m.range.first, m.range.last + 1, slack)
    }.toList()

    private fun joined(line: String, a: Quantity, b: Quantity) = JOINER.matches(line.substring(a.end, b.start))

    /**
     * Two neighboring pound and ounce quantities as one: the same weight printed twice ("1 LB (16 OZ)",
     * "16 OZ (1 LB)") becomes one restated quantity; pounds followed by ounces under a pound ("1 LB 4
     * OZ") become their sum. Anything else is left as two quantities, which refuses the line.
     */
    private fun imperialPairs(line: String, qs: List<Quantity>, floor: Int): List<Quantity> {
        val out = mutableListOf<Quantity>()
        var i = 0
        while (i < qs.size) {
            val q = qs[i]
            val next = qs.getOrNull(i + 1)
            if (next != null && !q.metric && !next.metric && q.unit != next.unit && joined(line, q, next) &&
                q.grams.isNotEmpty() && next.grams.isNotEmpty()
            ) {
                val restated = precise(q, next, floor)
                if (restated != null) {
                    out += restated
                    i += 2
                    continue
                }
                if (q.unit == Unit.LB && next.unit == Unit.OZ && next.grams.all { it < GRAMS_PER_LB - 1 }) {
                    val sums = q.grams.flatMap { a -> next.grams.map { b -> a + b } }.toSet()
                    out += Quantity(sums, metric = false, unit = Unit.LB, start = q.start, end = next.end, slack = q.slack + next.slack)
                    i += 2
                    continue
                }
            }
            out += q; i++
        }
        return out
    }

    /**
     * [a] and [b] as one weight printed twice, when their single in-band readings agree within the
     * more precise printing's rounding: the more precise printing's weight (on a tie, the metric
     * one). Null when they don't agree. The pair is [Quantity.restated] (two units that confirm each
     * other) only when the coarser printing is itself within [CONFIRMS] of the weight: "0.4 lb (179 g)"
     * agrees, but 0.4 lb covers 159 to 204 g, so it can't tell 179 from a misread 170.
     */
    private fun precise(a: Quantity, b: Quantity, floor: Int): Quantity? {
        val x = inBand(a, floor) ?: return null
        val y = inBand(b, floor) ?: return null
        if (!agree(x, y, minOf(a.slack, b.slack))) return null
        val confirms = maxOf(a.slack, b.slack) <= CONFIRMS * maxOf(x, y)
        val first = when {
            // Two coarse printings ("0.2 kg (0.4 lb)") pin nothing down: the bag's metric figure stands.
            !confirms && a.metric != b.metric -> a.metric
            a.slack < b.slack -> true
            b.slack < a.slack -> false
            else -> a.metric || !b.metric
        }
        val keep = if (first) a else b
        return keep.copy(grams = setOf(if (first) x else y), start = minOf(a.start, b.start), end = maxOf(a.end, b.end), restated = confirms)
    }

    /**
     * Two printings of one weight: labels round a conversion to the gram (8 oz as 227 g, 5 lb as 2268
     * or 2270 g), so they may differ by 2% of the weight, at least 1 g and at most 2 g; an amount
     * with decimals may also be off by half its last place ("2.2 lb" for 1 kg). On a small bag that
     * keeps a misread digit out: 1 oz is 28 g, so "1 OZ (26 G)" doesn't agree. A digit misread as
     * another digit moves a larger figure by 6 or more.
     */
    private fun agree(a: Int, b: Int, slack: Double = 0.0) = abs(a - b) <= (0.02 * maxOf(a, b)).coerceIn(1.0, 2.0) + slack

    /** The single reading of [q] from [floor] to the band's top, or null when there is none or more than one. */
    private fun inBand(q: Quantity, floor: Int): Int? = q.grams.filter { it in floor..BAND.last }.distinct().singleOrNull()

    private fun readLine(line: String): LineReading? {
        val netLine = NET_LINE.containsMatchIn(line)
        // Off a net line, a recipe, serving, dose, scoop or "per" amount is never the bag's weight.
        if (!netLine && NOT_THE_BAG.containsMatchIn(line)) return null
        // A 1 oz sample states its weight on a net line; elsewhere a weight that small is a dose.
        val floor = if (netLine) BAND.first else OFF_NET_FLOOR
        val found = quantities(line)
        if (found.isEmpty()) return null
        // A weight with an amount that can't be read makes the whole line untrustworthy: reading only
        // the rest of it ("4 OZ" out of "I LB 4 OZ") would name the wrong weight.
        if (found.any { it.grams.isEmpty() }) return LineReading.Refused
        val qs = imperialPairs(line, found, floor)
        if (qs.size == 1) {
            // A weight smaller than any bag (a serving size, "Net Carbs 0g", "Suite 5G") is not the bag's.
            if (qs[0].grams.all { it < floor }) return null
            return inBand(qs[0], floor)?.let { LineReading.Weight(it, dual = qs[0].restated) } ?: LineReading.Refused
        }
        if (qs.size == 2 && qs[0].metric != qs[1].metric && joined(line, qs[0], qs[1])) {
            val one = precise(qs[0], qs[1], floor) ?: return LineReading.Refused
            return LineReading.Weight(one.grams.single(), dual = one.restated)
        }
        return LineReading.Refused
    }

    /**
     * The weight [text] states, or null when it states none that can be trusted. With [netLinesOnly]
     * only lines that say "net" count; otherwise those lines count when they state a weight, and
     * every line when none does. Every counted line must agree, or nothing is returned.
     */
    fun printed(text: String, netLinesOnly: Boolean): Printed? {
        val lines = text.lines()
        val net = lines.filter { NET_LINE.containsMatchIn(it) }.mapNotNull(::readLine)
        val readings = if (net.isNotEmpty() || netLinesOnly) net else lines.mapNotNull(::readLine)
        if (readings.isEmpty() || readings.any { it is LineReading.Refused }) return null
        val weights = readings.filterIsInstance<LineReading.Weight>()
        if (!weights.all { agree(it.grams, weights.first().grams) }) return null
        return Printed(weights.first().grams, weights.any { it.dual })
    }

    /** The label's net weight for the guess from the label text (which only fills blanks). */
    fun fromLabel(text: String): Int? = printed(text, netLinesOnly = false)?.grams

    /**
     * [model]'s bag size, unless the label's net line says otherwise in a way that can be trusted:
     * an agreeing dual declaration, or a single unit when [model] itself is outside [BAND]. A blank
     * stays blank, and a figure that matches the label within 5% is kept as it is.
     */
    fun reconcile(model: Int?, labelText: String): Int? {
        if (model == null) return null
        val label = printed(labelText, netLinesOnly = true) ?: return model
        if (abs(model - label.grams) <= SAME * label.grams) return model
        return if (label.dual || model !in BAND) label.grams else model
    }
}
