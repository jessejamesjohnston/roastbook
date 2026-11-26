package dev.roastbook.app.extraction

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The printed-weight rules held against a wide set of label texts: US and metric, restated weights
 * in brackets in both orders, a period between tokens, "0Z" for "OZ", multipacks, cold brew, recipes,
 * nutrition panels, prices, addresses, ranges, split lines, recognition damage and the band's edges.
 * Each text carries the bag's true weight, or none when the text doesn't say which weight is the
 * bag's. For every text and every kind of model figure the rules must:
 * - never turn a right figure wrong, and never replace any figure with a different wrong one;
 * - leave every figure as it is when the text doesn't name the bag's weight;
 * - guess only the true weight, or nothing.
 * The one exception is listed where it is checked: a digit misread as another digit on a label
 * that prints one unit can't be told from a real weight, so it may be guessed.
 */
class NetWeightLabelsTest {
    private val oz = 28.35
    private val lb = 453.6
    private fun g(v: Double) = v.roundToInt()

    private data class Label(val text: String, val truth: Int?, val kind: String)

    private val labels: List<Label> = buildList {
        fun add(text: String, truth: Int?, kind: String) { add(Label(text, truth, kind)) }
        val ozSizes = listOf(2.0, 4.0, 5.0, 8.0, 10.0, 10.5, 11.0, 12.0, 16.0, 20.0, 24.0, 32.0, 40.0, 80.0)
        for (o in ozSizes) {
            val w = g(o * oz)
            val s = if (o % 1.0 == 0.0) o.toInt().toString() else o.toString()
            for (net in listOf("NET WT", "NET WT.", "Net Wt", "Net wt.", "NET WEIGHT", "Net Weight:", "NET", "Net Contents")) {
                add("$net $s OZ", w, "us-single")
                add("$net $s OZ. (${w}g)", w, "us-dual-dot")
                add("$net $s OZ ($w g)", w, "us-dual")
                add("$net ${s}oz/${w}g", w, "us-dual-slash")
                add("$net $s oz | $w g", w, "us-dual-bar")
                add("$net $s OZ (${w}G)", w, "us-dual")
                add("$net $s OZ. / $w g", w, "us-dual-dot")
                add("$net $s 0Z", w, "us-zero-z")
                add("$net $s 0Z ($w g)", w, "us-zero-z")
            }
        }
        val pounds = listOf("1/2" to 0.5, "½" to 0.5, "3/4" to 0.75, "¾" to 0.75, "1" to 1.0, "1 1/2" to 1.5, "1½" to 1.5,
            "2" to 2.0, "2.2" to 2.2, "2.5" to 2.5, "3" to 3.0, "5" to 5.0)
        for ((s, v) in pounds) {
            val w = g(v * lb)
            val ozs = (v * 16).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
            for (net in listOf("NET WT", "Net Wt.", "NET WEIGHT")) {
                add("$net $s LB", w, "lb-single")
                add("$net $s LBS", w, "lb-single")
                add("$net $s lb ($w g)", w, "lb-dual")
                add("$net $s LB. (${w}g)", w, "lb-dual-dot")
                add("$net $s LB ($ozs OZ)", w, "lb-oz-equiv")
                add("$net $ozs OZ ($s LB)", w, "oz-lb-equiv")
                add("$net $s LB / $ozs OZ", w, "lb-oz-equiv")
                add("$net $s LB ($ozs OZ) ${w}g", w, "lb-oz-g")
                add("$net $ozs OZ ($s LB) $w G", w, "oz-lb-g")
            }
        }
        add("5 LB BAG", 2268, "lb-bag"); add("NET WT 5 LB BAG", 2268, "lb-bag")
        add("NET WT 5 LB (2.27 KG)", 2268, "lb-kg"); add("NET WT 5 LB (2,268 g)", 2268, "lb-thou")
        add("Net 2.2 lbs (1kg)", 1000, "lb-kg"); add("NET WT 2.2 LB (1 KG)", 1000, "lb-kg")
        for (t in listOf("NET WT 6.6 LB (3 KG)", "NET WT 3 KG", "NET WT 3 KG (6 LB 10 OZ)")) add(t, 3000, "big")
        for (t in listOf("NET WT 5 KG", "NET WT 11 LB (5 KG)")) add(t, 5000, "big")
        for ((l, o) in listOf(1 to 4, 1 to 8, 1 to 2, 2 to 0, 1 to 0, 2 to 3)) {
            val w = g(l * lb + o * oz)
            add("NET WT $l LB $o OZ", w, "compound"); add("NET WT $l LB $o OZ ($w g)", w, "compound-dual")
            add("NET WT $l LB $o OZ (${w}g)", w, "compound-dual")
        }
        for (w in listOf(125, 200, 227, 250, 340, 454, 500, 750, 1000, 2500)) {
            for (f in listOf("%dg", "%d g", "%d g ℮", "%dg e", "%d G", "%d grams", "Net weight %dg", "Net weight: %d g ℮", "NET WT %dG",
                "Poids net : %d g", "Peso neto %d g", "Peso netto %d g", "Netto %d g", "Nettogewicht: %d g", "Inhalt: %d g",
                "内容量：%dg", "内容量 %dg", "Nett %dg", "Nett wt %dg")) add(f.replace("%d", "$w"), w, "eu")
            val kg = w / 1000.0
            val kgs = setOf(kg.toString().removeSuffix(".0"), kg.toString().removeSuffix(".0").replace('.', ','),
                String.format(java.util.Locale.US, "%.3f", kg), String.format(java.util.Locale.US, "%.3f", kg).replace('.', ','))
            for (k in kgs) { add("Net weight $k kg", w, "eu-kg"); add("NET ${k}KG", w, "eu-kg") }
            if (w >= 1000) {
                val thou = String.format(java.util.Locale.US, "%,d", w)
                add("Net weight $thou g", w, "thou"); add("NET WT ${thou}g", w, "thou")
                add("Net weight ${thou.replace(',', '.')} g", w, "thou-dot")
                add("Net weight ${thou.replace(',', ' ')} g", w, "thou-space")
            }
        }
        for ((t, w) in listOf("Net weight 0,5 kg" to 500, "Net weight 0.5kg" to 500, "NET WT 500g (1.1 lb)" to 500, "NET WT 1kg (2.2lb)" to 1000,
            "NET WT 250g (8.8 oz)" to 250, "NET WT 250 g / 8.8 oz" to 250, "NET WT 1 kg / 35.3 oz" to 1000, "NET WT 1 KG (2 LB 3 OZ)" to 1000,
            "NET WT 340 g e (12 oz)" to 340, "approx. 340 g" to 340, "NET WT approx 12 oz (340 g)" to 340)) add(t, w, "eu-dual")
        for ((t, w) in listOf("NET WT 2 x 250 g" to null, "NET WT 250g x 2" to null, "NET WT 500 g (2 x 250 g)" to 500,
            "NET WT 2 x 12 OZ (340 g)" to null, "NET WT 12 OZ (340 g) x 2" to null, "NET WT 24 OZ (2 x 12 OZ)" to 680,
            "NET WT 10 g x 10 bags (100 g)" to 100, "10 drip bags x 12 g" to null, "Pack of 3 x 340g" to null)) add(t, w, "multi")
        for (t in listOf("NET 12 FL OZ (355 mL)", "NET 12 FL. OZ. (355 ml)", "12 fl oz", "Net Contents 32 fl oz (946 mL)")) add(t, null, "coldbrew")
        for ((t, w) in listOf(
            "Serving size 15 g\nMakes 30 cups\n12 OZ (340 g)" to 340, "Serving size 15 g\nNET WT 12 OZ (340 g)" to 340,
            "Nutrition Facts\nServing Size 2 tbsp (10g)\nTotal Fat 0g\nSodium 0mg\nNet Carbs 0g\nNET WT 12 OZ (340g)" to 340,
            "Nutrition Facts\nServing Size 2 tbsp (10g)\nTotal Fat 0g\nNet Carbs 0g" to null, "Brew 15 g per 250 ml\nNET WT 12 OZ" to 340,
            "Brew ratio 20 g coffee : 320 g water" to null, "Use 2 oz (57 g) per 32 oz water\n12 OZ" to 340,
            "Use 2 oz (57 g) per 32 oz water\nNET WT 12 OZ (340 g)" to 340, "Roasted 11/12\nLot 120Z\nNET WT 12 OZ" to 340,
            "Roasted on 11/12/2026\nNET WT. 12 OZ. (340g)" to 340, "Lot 4521 · Batch 12\nNET WT 12 OZ (340 g)" to 340, "$18.99 · 12 oz" to 340,
            "$1.50/oz\nNET WT 12 OZ" to 340, "$24/lb" to null, "1234 Main St, Suite 5G\nPortland OR 97214\nNET WT 12 OZ" to 340,
            "1234 Main St, Suite 5G\nPortland OR 97214\n12 OZ" to 340, "Elevation 1,800-2,100 masl\nNET WT 12 OZ (340 g)" to 340,
            "Elevation 1,800 m\nNet wt 250 g" to 250, "Score 86.5\nNET WT 12 OZ" to 340, "bluebottlecoffee.net\nNET WT 12 OZ (340 g)" to 340,
            "roaster.net · 8 oz" to 227, "NET WT 250-300 g" to null, "NET WT\n12 OZ (340 g)" to 340, "NET WT 12\nOZ (340 g)" to 340,
            "NET WT 12 OZ\n(340 g)" to 340, "NET WT 12 OZ (340" to 340, "NET WT 12 OZ\n340 g" to 340,
        )) add(t, w, "context")
        for ((t, w) in listOf(
            "NET WT 12 OZ (3409)" to 340, "NET WT 12 OZ (34Og)" to 340, "NET WT 12 0Z (340 g)" to 340, "NET WT 12 0Z" to 340, "12 0Z" to 340,
            "NET WT 12 OZ (340 9)" to 340, "NET WT 12 OZ (840 g)" to 340, "NET WT 12 OZ (346 g)" to 340, "NET WT 72 OZ (340 g)" to 340,
            "NET WT 12 OZ (3 40 g)" to 340, "NET WT 1 2 OZ (340 g)" to 340, "NET WT I2 OZ (340 g)" to 340, "NET WT 12 O2 (340 g)" to 340,
            "NET WT 12 OZ (34 0 g)" to 340, "NET WT 12OZ(340G)" to 340, "NETWT12OZ340G" to 340, "NET WT 12 oz (340 gr)" to 340,
            "NET WT 8 OZ (227 g)" to 227, "NET WT 8 OZ (221 g)" to 227, "NET WT 8 OZ (277 g)" to 227, "NET WT 6 OZ (227 g)" to 227,
            "NET WT 5 LB (2.27 KG)" to 2268, "NET WT 5 LB (2.21 KG)" to 2268, "NET WT 5 LB (2,27 KG)" to 2268, "NET WT S LB (2.27 KG)" to 2268,
            "NET WT 0.5 LB (227 G)" to 227, "NET WT 0.5 LB (221 G)" to 227, "NET WT 0.5 LB (237 G)" to 227, "NET WT 0.5 LB (207 G)" to 227,
            "NET WT 2.2 LB (1000 G)" to 1000, "NET WT 2.2 LB (1008 G)" to 1000, "NET WT 2.2 LB (980 G)" to 1000, "NET WT 1.1 LB (500 G)" to 500,
            "NET WT 1.1 LB (480 G)" to 500, "NET WT 1.1 LB (520 G)" to 500, "NET WT 0.2 LB (91 G)" to 91, "NET WT 0.2 LB (97 G)" to 91,
            "NET WT 0.3 LB (136 G)" to 136, "NET WT 0.3 LB (130 G)" to 136, "NET WT 0.3 LB (118 G)" to 136, "NET WT 0.3 LB (156 G)" to 136,
            "NET WT 3.5 OZ (100 G)" to 100, "NET WT 3.5 OZ (106 G)" to 100, "NET WT 1.8 OZ (50 G)" to 50, "NET WT 1.8 OZ (56 G)" to 50,
            "NET WT 2 OZ (57 G)" to 57, "NET WT 1 OZ (28 G)" to 28, "NET WT 7 OZ" to 28, "NET WT 1.5 OZ (43 g)" to 43,
            "Net weight 0,5 kg (17.6 oz)" to 500, "Net weight 0,5 kg (16.6 oz)" to 500, "Net weight 0,6 kg (17.6 oz)" to 500,
            "Net weight 0,25 kg (8.8 oz)" to 250, "Net weight 0,2 kg (8.8 oz)" to 250, "Net weight 0,3 kg (8.8 oz)" to 250,
            "Net weight 0,2 kg (7.1 oz)" to 200, "Net weight 0,2 kg (7.7 oz)" to 200, "Net weight 0,1 kg (3.5 oz)" to 100,
            "Net weight 0,1 kg (3.6 oz)" to 100,
        )) add(t, w, "ocr")
        for (w in listOf(49, 50, 51, 2499, 2500, 2501, 2268, 3000)) {
            add("NET WT $w g", w, "edge")
            add("NET WT $w g (${String.format(java.util.Locale.US, "%.1f", w / oz)} oz)", w, "edge-dual")
        }
    }

    /**
     * Texts whose single printed unit was misread digit for digit ("7 OZ" on a 1 oz sample): the
     * misread reads as a real weight, so the guess can't avoid it, and a figure already outside the
     * band gives way to it (a wrong figure for another). A plausible bag size is always kept.
     */
    private val undetectableGuesses = setOf("NET WT 7 OZ")

    private fun close(a: Int?, b: Int) = a != null && abs(a - b) <= 0.05 * b

    /** The figures a model could hand over for [l]: blank, right, plausibly wrong, and outside what a bag holds. */
    private fun models(l: Label): List<Int?> = if (l.truth == null) listOf(null, 227, 250, 340, 500, 680, 12, 3409) else {
        val t = l.truth
        val wrongIn = (t * 0.7).toInt().let { if (it in NetWeight.BAND) it else (t * 1.4).toInt() }
        listOf(null, t, wrongIn, if (t > 100) 12 else 3409, t * 10)
    }

    @Test fun aRightBagSizeIsNeverMadeWrongAndAWrongOneIsNeverMadeDifferentlyWrong() {
        assert(labels.size > 900) { "only ${labels.size} labels" }
        val problems = mutableListOf<String>()
        for (l in labels) for (m in models(l)) {
            if (m == null) continue
            val r = NetWeight.reconcile(m, l.text)
            when {
                r == m -> {}
                l.truth == null -> problems += "${l.kind}: \"${l.text}\" changed $m to $r with no weight to trust"
                // A figure outside the band given way to an undetectable single-unit misread: wrong before, wrong after.
                !close(r, l.truth) && !(l.text in undetectableGuesses && m !in NetWeight.BAND) ->
                    problems += "${l.kind}: \"${l.text}\" changed $m to $r (true ${l.truth})"
                close(m, l.truth) -> problems += "${l.kind}: \"${l.text}\" changed a right $m to $r"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    /** Design rule (ii): a plausible figure gives way only to two printed units that agree with each other. */
    @Test fun aPlausibleFigureGivesWayOnlyToTwoAgreeingUnits() {
        for (l in labels) for (m in models(l)) {
            if (m == null || m !in NetWeight.BAND) continue
            val r = NetWeight.reconcile(m, l.text)
            if (r != m) assert(NetWeight.printed(l.text, netLinesOnly = true)?.dual == true) { "\"${l.text}\": $m to $r on one unit" }
        }
    }

    @Test fun aGuessIsTheTrueWeightOrNothing() {
        val problems = mutableListOf<String>()
        for (l in labels) {
            val guess = NetWeight.fromLabel(l.text) ?: continue
            if (l.text in undetectableGuesses) continue
            if (l.truth == null || !close(guess, l.truth)) problems += "${l.kind}: \"${l.text}\" guessed $guess (true ${l.truth})"
        }
        assertEquals(emptyList<String>(), problems)
    }
}
