package dev.roastbook.app.extraction

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The net weight a bag's label text states, in grams, and what it says about a model's bag size.
 *
 * Text recognition misreads weights in a few set ways: a zero as the letter O ("2Ooz"), the o of
 * "oz" as a zero ("120z" for "12oz"), and a "g" as a 9 ("(3409)" for "(340g)"). A model handed that
 * text can take the misread gram figure for the weight, while the ounces on the same line still say
 * what the bag holds. So the ounces (or pounds, kilograms, grams) are read with those slips in mind,
 * and a model's bag size that disagrees with them gives way. A blank bag size is never filled here:
 * a model's blank stays blank, and the label-text guess fills blanks through [fromLabel].
 */
object NetWeight {
    /** Weights a coffee bag is sold in; anything outside is a misread, not a bag. */
    private val PLAUSIBLE = 50..5000

    /** Agreement within this fraction counts as the same weight (rounded conversions, "about 12 oz"). */
    private const val SAME = 0.05

    private val GRAMS = Regex("""(\d{2,4}(?:[.,]\d)?)\s*(?:g|gr|grams?)\b""", RegexOption.IGNORE_CASE)
    // Up to three digits, any of them possibly a letter O, then "oz" with its o possibly a zero.
    // Backtracking tries the longest number first, so "120 oz" stays 120 and "120z" becomes 12 oz.
    private val OUNCES = Regex("""(?<![\d.,])(\d[\dO]{0,2}(?:[.,]\d{1,2})?)\s*(?:[o0]z|ounces?)\b""", RegexOption.IGNORE_CASE)
    private val KILOS = Regex("""(?<![\d.,])(\d(?:[.,]\d{1,2})?)\s*(?:kg|kilograms?)\b""", RegexOption.IGNORE_CASE)
    private val POUNDS = Regex("""(?<![\d.,])(\d(?:[.,]\d{1,2})?)\s*(?:lbs?|pounds?)\b""", RegexOption.IGNORE_CASE)
    private val NET_LINE = Regex("""\bnet\b|\bnett\b""", RegexOption.IGNORE_CASE)

    private fun number(s: String): Double? = s.replace('O', '0').replace('o', '0').replace(',', '.').toDoubleOrNull()

    /** Every plausible weight in [text], in grams, in the order grams, ounces, kilograms, pounds. */
    private fun weights(text: String): List<Int> = buildList {
        for ((pattern, grams) in listOf(GRAMS to 1.0, OUNCES to 28.35, KILOS to 1000.0, POUNDS to 453.6)) {
            pattern.findAll(text).forEach { m ->
                number(m.groupValues[1])?.let { (it * grams).roundToInt() }?.takeIf { it in PLAUSIBLE }?.let(::add)
            }
        }
    }

    private fun same(a: Int, b: Int) = abs(a - b) <= SAME * b

    /** The label's net weight for the guess from the label text: the first plausible one, grams first. */
    fun fromLabel(text: String): Int? = weights(text).firstOrNull()

    /**
     * [model]'s bag size, unless the label text contradicts it: then the weight the label states.
     * The label decides only when it is clear which weight is the bag's: one on a "net" line, or two
     * or more weights it prints, all agreeing ("12 oz (340 g)"). A lone weight elsewhere may be a
     * brew recipe or a shot size, not the bag. Otherwise, and when the label states none, [model]
     * stands.
     */
    fun reconcile(model: Int?, labelText: String): Int? {
        if (model == null) return null
        val all = weights(labelText)
        if (all.isEmpty() || all.any { same(model, it) }) return model
        val onNetLine = labelText.lines().filter { NET_LINE.containsMatchIn(it) }.flatMap(::weights)
        val agreeing = all.size >= 2 && all.all { same(it, all.first()) }
        return onNetLine.firstOrNull() ?: all.first().takeIf { agreeing } ?: model
    }
}
