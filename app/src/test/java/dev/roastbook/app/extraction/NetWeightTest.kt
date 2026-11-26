package dev.roastbook.app.extraction

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The printed net weight, read as one quantity, and when it may replace a model's bag size. The
 * tables are generated in both directions: label lines that must read to the right grams, lines that
 * must be refused, and every single-character recognition slip of a good line, which must read to
 * the right grams or be refused, and must never turn a right bag size into a wrong one.
 */
class NetWeightTest {
    private fun oz(n: Double) = (n * 28.35).roundToInt()
    private fun lb(n: Double) = (n * 453.6).roundToInt()

    /** A net line and the grams it states; [dual] when both unit systems are printed. */
    private data class Line(val text: String, val grams: Int, val dual: Boolean)

    private fun fmt(n: Double) = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    /** Realistic US and metric net lines, each with the weight a person reads from it. */
    private val good: List<Line> = buildList {
        for (n in listOf(2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 16.0, 18.0, 20.0, 24.0, 28.0, 32.0, 40.0, 64.0, 80.0)) {
            val g = oz(n)
            val o = fmt(n)
            add(Line("NET WT $o OZ", g, false))
            add(Line("Net Wt. ${o}oz", g, false))
            add(Line("NET WT $o OZ ($g g)", g, true))
            add(Line("Net wt. ${o}oz (${g}g) | Whole bean", g, true))
            add(Line("NET WT ${o}OZ / ${g}G", g, true))
            add(Line("Net weight $g g / $o oz", g, true))
            add(Line("NET WEIGHT: $o ounces ($g grams)", g, true))
        }
        for (n in listOf(1.0, 2.0, 2.5, 3.0, 5.0)) {
            val g = lb(n)
            add(Line("NET WT ${fmt(n)} LB", g, false))
            add(Line("Net Wt ${fmt(n)} lbs ($g g)", g, true))
            add(Line("NET WT ${fmt(n)} POUNDS", g, false))
        }
        for ((text, n) in listOf("1/4" to 0.25, "1/2" to 0.5, "3/4" to 0.75, "½" to 0.5, "¾" to 0.75, "1 1/2" to 1.5, "1½" to 1.5, "2 1/2" to 2.5)) {
            val g = lb(n)
            add(Line("NET WT $text LB", g, false))
            add(Line("Net Wt. $text lb ($g g)", g, true))
        }
        for ((l, o) in listOf(1 to 4, 1 to 8, 2 to 0, 2 to 8, 1 to 12)) {
            val g = lb(l.toDouble()) + oz(o.toDouble())
            add(Line("NET WT $l LB $o OZ", g, false))
            add(Line("NET WT $l LB $o OZ ($g g)", g, true))
            add(Line("Net wt $l lb, $o oz", g, false))
        }
        for (g in listOf(100, 125, 200, 227, 250, 300, 340, 350, 454, 500, 750, 1000)) {
            add(Line("Net weight $g g", g, false))
            add(Line("NET ${g}G", g, false))
            add(Line("Net wt. ${g}g · whole bean", g, false))
            add(Line("Nett $g grams", g, false))
        }
        for ((text, g) in listOf("0.5" to 500, "0,5" to 500, "1" to 1000, "1.5" to 1500, "1,5" to 1500, "2" to 2000, "2.268" to 2268, "2,5" to 2500)) {
            add(Line("Net weight $text kg", g, false))
            add(Line("NET WT $text KG", g, false))
        }
        add(Line("NET WT 5 LB (2,268 g)", 2268, true))
        add(Line("NET WT 5 LB (2.268 g)", 2268, true))
        add(Line("Net 1,000 g", 1000, false))
        add(Line("Net weight 1 kg / 2.2 lb", 1000, true))
        add(Line("NET WT 2.2 LB (1 KG)", 1000, true))
        // 1.1 lb covers 476 to 522 g: it agrees with 500 g but is too coarse to confirm it.
        add(Line("Net 500 g (1.1 lb)", 500, false))
    }

    @Test fun everyRealisticNetLineReadsToTheWeightAPersonReads() {
        assert(good.size > 250) { "only ${good.size} lines" }
        for (l in good) {
            assertEquals(l.text, NetWeight.Printed(l.grams, l.dual), NetWeight.printed(l.text, netLinesOnly = true))
            assertEquals(l.text, l.grams, NetWeight.fromLabel(l.text))
        }
    }

    @Test fun linesThatNameNoTrustworthyWeightAreRefused() {
        val refused = listOf(
            // No net weight at all.
            "Net zero carbon roastery", "Net price $12.99", "Lot 120Z net", "Net wt. Lot 1202", "Roast no. 12 net",
            "Net wt roasted 03/11/26", "Net: 1200 Grand Ave", "NET WT 120Z",
            // A pair that disagrees.
            "NET WT 12 OZ (284 g)", "NET WT 10 OZ (340 g)", "Net 340 g / 10 oz", "NET WT 1 LB (340 g)",
            // Two weights in one unit system.
            "NET WT 12 OZ 8 OZ", "Net 250 g 500 g",
            // Outside what a retail bag holds.
            "Net wt 9 kg", "NET WT 10 g", "Net wt 0.5 oz", "NET WT 20 LB", "NET WT 3 kg",
            // A side that reads outside the band.
            "Net 12 g / 12 oz",
        )
        for (t in refused) assertNull(t, NetWeight.printed(t, netLinesOnly = true))
        // Lines without "net" don't count toward a correction at all.
        assertNull(NetWeight.printed("12 oz (340 g)", netLinesOnly = true))
    }

    /** Single-character recognition slips: a digit read as a look-alike letter, or as another digit. */
    private val slips = listOf('0' to 'O', '0' to 'o', '1' to 'I', '1' to 'l', '5' to 'S', '8' to 'B', '2' to 'Z', '7' to '1', '1' to '7')
    private val letterSlips = slips.filter { !it.second.isDigit() }

    private fun mutations(text: String): List<Pair<String, Pair<Char, Char>>> = buildList {
        text.forEachIndexed { i, c ->
            for (s in slips) if (s.first == c) add(text.substring(0, i) + s.second + text.substring(i + 1) to s)
        }
    }

    @Test fun aSlipOnADualLineReadsRightOrIsRefused() {
        var checked = 0
        for (l in good.filter { it.dual }) for ((m, _) in mutations(l.text)) {
            val read = NetWeight.printed(m, netLinesOnly = true)
            assert(read == null || read.grams == l.grams) { "\"$m\" (from \"${l.text}\") read as $read" }
            checked++
        }
        assert(checked > 500) { "only $checked slips" }
    }

    @Test fun aLetterSlipOnASingleUnitLineReadsRightOrIsRefused() {
        for (l in good.filter { !it.dual }) for ((m, s) in mutations(l.text)) {
            if (s !in letterSlips) continue
            val read = NetWeight.printed(m, netLinesOnly = true)
            assert(read == null || read.grams == l.grams) { "\"$m\" (from \"${l.text}\") read as $read" }
        }
    }

    /**
     * A digit read as another digit on a single-unit line ("70 OZ" for "10 OZ") reads as a different
     * weight that can't be told from a real one. Those lines never override a bag size that is itself
     * plausible, so no slip of any kind can turn a right bag size into a wrong one.
     */
    @Test fun noSlipEverTurnsARightBagSizeIntoAWrongOne() {
        var checked = 0
        for (l in good) for ((m, _) in mutations(l.text) + (l.text to ('-' to '-'))) {
            assertEquals("\"$m\" (from \"${l.text}\")", l.grams, NetWeight.reconcile(l.grams, m))
            checked++
        }
        assert(checked > 1000) { "only $checked cases" }
    }

    @Test fun aMisreadBagSizeGivesWayToANetLineThatCanBeTrusted() {
        for (l in good) {
            // A gram figure with its "g" read as a 9 is outside the band: any trustworthy net line corrects it.
            val misread = l.grams * 10 + 9
            if (misread !in NetWeight.BAND) assertEquals(l.text, l.grams, NetWeight.reconcile(misread, l.text))
            // A plausible wrong figure gives way only to an agreeing pair.
            val plausibleWrong = if (l.grams > 1000) l.grams / 2 else l.grams * 2
            assertEquals(l.text, if (l.dual) l.grams else plausibleWrong, NetWeight.reconcile(plausibleWrong, l.text))
        }
    }

    /** Correct readings on common US labels that the first version of the rule overwrote. */
    @Test fun aCorrectBagSizeOnAFractionOrCompoundLabelIsKept() {
        val rows = listOf(
            227 to "NET WT 1/2 LB", 227 to "NET WT 1/2 LB (2279)", 340 to "NET WT 3/4 LB",
            567 to "NET WT 1 LB 4 OZ", 567 to "NET WT 1 LB 4 OZ (5679)", 340 to "NET WT I2 OZ",
            284 to "NET WT 70 OZ", 227 to "NET WT 6 OZ", 227 to "Brew 2 oz (57 g) per cup\nBag holds 8 oz",
        )
        for ((model, label) in rows) assertEquals("$model on \"$label\"", model, NetWeight.reconcile(model, label))
    }

    @Test fun aBagSizeThatAgreesWithTheLabelOrHasNothingToCheckAgainstIsKept() {
        val cases = listOf(
            344 to "Net wt. 12 oz (340 g)", 340 to "Washed Caturra, notes of plum and cocoa", 340 to "",
            340 to "Also in 5 lb bags\nTry our 8 oz sampler", 340 to "Brew 20g coffee with 300g water",
            340 to "Pulls a 2 oz shot", 340 to "Best as a 250 g pour over", 340 to "Lot 120Z", 340 to "Brazil 1O0z blend",
        )
        for ((model, label) in cases) assertEquals("$model on \"$label\"", model, NetWeight.reconcile(model, label))
    }

    /** The same weight printed twice in pounds and ounces, in either order, is one weight, never a sum. */
    @Test fun aRestatedPoundAndOunceWeightIsOneWeight() {
        for ((pounds, ounces) in listOf("1/2" to "8", "3/4" to "12", "1" to "16", "2" to "32", "1 1/2" to "24")) {
            val grams = lb(eval(pounds))
            for (t in listOf("NET WT $pounds LB ($ounces OZ)", "NET WT $ounces OZ ($pounds LB)", "Net Wt. $pounds lb / $ounces oz", "NET WT $pounds LB ($ounces OZ) $grams g")) {
                assertEquals(t, NetWeight.Printed(grams, true), NetWeight.printed(t, netLinesOnly = true))
            }
        }
    }

    private fun eval(s: String): Double = s.split(' ').sumOf { part -> part.split('/').let { if (it.size == 2) it[0].toDouble() / it[1].toDouble() else it[0].toDouble() } }

    @Test fun aPeriodBetweenTokensAndAZeroForTheOOfOzStillRead() {
        assertEquals(oz(12.0), NetWeight.fromLabel("NET WT. 12 OZ. (340g)"))
        assertEquals(oz(12.0), NetWeight.fromLabel("NET WT 12 OZ."))
        assertEquals(oz(12.0), NetWeight.fromLabel("NET WT 12 0Z"))
        assertEquals(340, NetWeight.fromLabel("NT WT. 12 0Z || 340 G"))
        // Off a net line, or with no space before it, "0Z" stays unread.
        assertNull(NetWeight.fromLabel("Mix 20 0z ice"))
        assertNull(NetWeight.fromLabel("NET WT 120Z"))
        assertNull(NetWeight.fromLabel("Lot 120Z net"))
    }

    /** A pair whose coarser side can't pin the weight down never overrides a plausible figure. */
    @Test fun aCoarsePairNeverReplacesAPlausibleFigure() {
        assertEquals(250, NetWeight.reconcile(250, "Net weight 0,2 kg (8.8 oz)"))
        assertEquals(170, NetWeight.reconcile(170, "NET WT 0.4 LB (179 G)"))
        assertEquals(200, NetWeight.reconcile(200, "Net weight 0.2 kg (0.4 lb)"))
        assertEquals(227, NetWeight.reconcile(227, "NET WT 0.5 LB (207 G)"))
        assertEquals("a 1 oz sample is a bag", 28, NetWeight.reconcile(28, "NET WT 7 OZ"))
    }

    @Test fun aMultipackARangeOrASplitNumberNamesNoWeight() {
        for (t in listOf("NET WT 2 x 250 g", "NET WT 250g x 2", "NET WT 12 OZ (340 g) x 2", "Pack of 3 x 340g", "NET WT 250-300 g", "Net weight 2 500 g")) {
            assertNull(t, NetWeight.printed(t, netLinesOnly = false))
        }
    }

    /** Off a net line a weight must look like a bag (50 g or more) and not like a recipe, serving, dose, scoop or "per" amount. */
    @Test fun aRecipeServingOrSmallWeightOffANetLineIsNeverTheBagsWeight() {
        for (t in listOf("Use 30 g per liter of water", "Serving size 30 g", "Scoop 28 g", "1 sachet = 28 g", "Each drip bag 25 g",
            "Brew 60 g per liter", "Dose 18 g", "40 g", "Recipe: 250 g of water", "Pour 300 g water")) {
            assertNull(t, NetWeight.fromLabel(t))
        }
        assertEquals(28, NetWeight.fromLabel("NET WT 1 OZ (28 G)"))
        assertEquals(30, NetWeight.fromLabel("NET WT 30 g"))
        assertEquals(340, NetWeight.fromLabel("340 g"))
    }

    /** A pair whose sides disagree beyond the precise side's rounding isn't trusted at all. */
    @Test fun aSmallPairThatDisagreesByTwoGramsIsNotTrusted() {
        assertNull(NetWeight.printed("NET WT 1 OZ (26 G)", netLinesOnly = true))
        assertEquals(28, NetWeight.reconcile(28, "NET WT 1 OZ (26 G)"))
        assertEquals(NetWeight.Printed(28, true), NetWeight.printed("NET WT 1 OZ (28 G)", netLinesOnly = true))
    }

    @Test fun eachPartOfTheRuleHoldsOnItsOwnLine() {
        // A pound followed by a pound or more of ounces is neither one weight nor a sum.
        assertNull(NetWeight.printed("NET WT 1 LB (18 OZ)", netLinesOnly = true))
        // Two net lines that disagree.
        assertNull(NetWeight.printed("NET WT 12 OZ\nNet weight 250 g", netLinesOnly = true))
        // "NT WT" and the estimated-weight mark are read, exactly.
        assertEquals(NetWeight.Printed(340, true), NetWeight.printed("NT WT 12 OZ (340 g)", netLinesOnly = true))
        assertEquals(NetWeight.Printed(340, true), NetWeight.printed("NET WT 340 g e (12 oz)", netLinesOnly = true))
        assertEquals(NetWeight.Printed(340, true), NetWeight.printed("NET WT 340 g ℮ 12 oz", netLinesOnly = true))
        // "0Z" is ounces only after the net words, never in a code that happens to share a line with "net".
        assertNull(NetWeight.fromLabel("Lot 12 0Z net"))
        assertNull(NetWeight.fromLabel("Model 2 0Z net 4"))
        // Both printings coarse: the guess follows the metric side, or there is none.
        assert(NetWeight.fromLabel("NET WT 0.2 kg (0.4 lb)") in setOf(200, null))
        assert(NetWeight.fromLabel("NET WT 0.3 kg (0.7 lb)") in setOf(300, null))
    }

    @Test fun aBlankBagSizeIsNeverFilledByTheCorrection() {
        assertNull(NetWeight.reconcile(null, "Net wt. 12 oz (340 g)"))
    }

    @Test fun theGuessReadsAWeightAnywhereWhenNoNetLineStatesOne() {
        assertEquals(oz(12.0), NetWeight.fromLabel("Whole bean\n12 oz (340 g)"))
        assertEquals(oz(20.0), NetWeight.fromLabel("Net wt. 2Ooz"))
        assertNull(NetWeight.fromLabel("Mix 20 0z ice"))
        assertNull("two disagreeing weights", NetWeight.fromLabel("Brew 20g coffee with 300g water"))
    }
}
