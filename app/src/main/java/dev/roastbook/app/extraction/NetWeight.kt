package dev.roastbook.app.extraction

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The net weight a bag's label text states, read as one quantity, and what it may say about a
 * model's bag size. Built so that it cannot turn a right bag size into a wrong one:
 *
 * - **A real grammar.** An amount is a whole number, a decimal ("1.5", "0,5"), a fraction ("1/2",
 *   "½") or a mixed number ("1 1/2"), followed by a real unit token (g, kg, oz, lb and their spelled
 *   forms) that ends at a word boundary. Pounds followed by ounces ("1 LB 4 OZ") are one quantity. A
 *   separator followed by three digits may be a thousands mark ("2,268 g") or a decimal point
 *   ("2.268 kg"); only a reading that lands in the plausible band counts, and two that both do are
 *   refused. Nothing without a unit is read, so lot codes, prices, dates and roast numbers are not.
 * - **Text recognition slips.** Inside an amount, a letter that is only ever a misread digit is read
 *   as that digit: O and o as 0, I and l as 1, S as 5, B as 8, Z as 2. Those slips read to the
 *   right value. A digit read as another digit ("70 oz" for "10 oz") cannot be seen on its own.
 * - **Dual declarations.** "12 oz (340 g)" and "340 g / 12 oz" must agree within rounding, or the
 *   line is refused. A digit slip almost never keeps both sides in step, so an agreeing pair is
 *   trusted.
 * - **When the label wins** ([reconcile]): only on a "net" line, only when that line reads
 *   unambiguously and inside the band, and then only if the pair agreed, or the model's own figure
 *   is outside the band (so a digit slip on a single unit can never replace a plausible reading).
 */
object NetWeight {
    /** Grams a retail coffee bag can hold: from a 2 oz sample to a 2.5 kg bag. */
    val BAND = 50..2500

    /** A model's figure this close to the label's is the same weight (rounded conversions). */
    private const val SAME = 0.05

    private const val GRAMS_PER_OZ = 28.35
    private const val GRAMS_PER_LB = 453.6

    /** What one line of label text states. */
    private sealed interface LineReading {
        /** One quantity, in grams; [dual] when it was printed in both unit systems that agree. */
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
            "\\s*(?i:(kilograms?|kilos?|kgs?|grams?|gr|g|ounces?|oz|pounds?|lbs?))(?![\\p{L}\\p{N}])",
    )
    private val NET_LINE = Regex("""\b(?:nett?|net\s*wt|netwt)\b""", RegexOption.IGNORE_CASE)
    /** What may sit between the two halves of a dual declaration or a pounds-and-ounces weight. */
    private val JOINER = Regex("""[\s()\[\]/|·,&+\-]*(?:and\s*)?[\s()\[\]/|·,&+\-]*""", RegexOption.IGNORE_CASE)

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
    private data class Quantity(val grams: Set<Int>, val metric: Boolean, val unit: Unit, val start: Int, val end: Int, val slack: Double = 0.0)

    /** Every quantity on [line]; one whose amount can't be read at all ("I LB") has no readings. */
    private fun quantities(line: String): List<Quantity> = QUANTITY.findAll(line).map { m ->
        val unit = unitOf(m.groupValues[2])
        val grams = amounts(m.groupValues[1]).map { (it * unit.grams).roundToInt() }.toSet()
        val decimals = Regex("[.,](\\w{1,2})$").find(m.groupValues[1].trim())?.groupValues?.get(1)?.length ?: 0
        val slack = if (decimals == 0) 0.0 else 0.5 * Math.pow(10.0, -decimals.toDouble()) * unit.grams
        Quantity(grams, unit.metric, unit, m.range.first, m.range.last + 1, slack)
    }.toList()

    private fun joined(line: String, a: Quantity, b: Quantity) = JOINER.matches(line.substring(a.end, b.start))

    /** Pounds followed by ounces ("1 LB 4 OZ") become one quantity. */
    private fun compound(line: String, qs: List<Quantity>): List<Quantity> {
        val out = mutableListOf<Quantity>()
        var i = 0
        while (i < qs.size) {
            val q = qs[i]
            val next = qs.getOrNull(i + 1)
            if (q.unit == Unit.LB && next != null && next.unit == Unit.OZ && joined(line, q, next)) {
                val sums = q.grams.flatMap { a -> next.grams.map { b -> a + b } }.toSet()
                out += Quantity(sums, metric = false, unit = Unit.LB, start = q.start, end = next.end)
                i += 2
            } else {
                out += q; i++
            }
        }
        return out
    }

    /**
     * Two printings of one weight: labels round a conversion to the gram (8 oz as 227 g, 5 lb as 2268
     * or 2270 g), so whole amounts differ by 2 g at most; an amount with decimals may also be off by
     * half its last place ("2.2 lb" for 1 kg). A digit misread as another digit moves a figure by 6
     * or more, so a pair it touches stops agreeing.
     */
    private fun agree(a: Int, b: Int, slack: Double = 0.0) = abs(a - b) <= 2.0 + slack

    /** The single in-band reading of [q], or null when there is none or more than one. */
    private fun inBand(q: Quantity): Int? = q.grams.filter { it in BAND }.distinct().singleOrNull()

    private fun readLine(line: String): LineReading? {
        val qs = compound(line, quantities(line))
        if (qs.isEmpty()) return null
        // A weight with an amount that can't be read makes the whole line untrustworthy: reading only
        // the rest of it ("4 OZ" out of "I LB 4 OZ") would name the wrong weight.
        if (qs.any { it.grams.isEmpty() }) return LineReading.Refused
        if (qs.size == 1) return inBand(qs[0])?.let { LineReading.Weight(it, dual = false) } ?: LineReading.Refused
        if (qs.size == 2 && qs[0].metric != qs[1].metric && joined(line, qs[0], qs[1])) {
            val a = inBand(qs[0]) ?: return LineReading.Refused
            val b = inBand(qs[1]) ?: return LineReading.Refused
            return if (agree(a, b, max(qs[0].slack, qs[1].slack))) LineReading.Weight(if (qs[0].metric) a else b, dual = true) else LineReading.Refused
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
