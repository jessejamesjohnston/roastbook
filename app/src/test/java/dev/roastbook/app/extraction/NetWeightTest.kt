package dev.roastbook.app.extraction

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A model's bag size against the net weight the label text states. Generated both ways: misreads
 * the printed weight must correct, and realistic readings it must leave alone.
 */
class NetWeightTest {
    private fun oz(n: Int) = (n * 28.35).roundToInt()
    private fun lb(n: Int) = (n * 453.6).roundToInt()

    /** How text recognition prints "N oz": clean, spaced, capitals, and with a letter O for the zero. */
    private fun ozSpellings(n: Int): List<String> = buildList {
        add("${n}oz"); add("$n oz"); add("${n}OZ"); add("$n Oz"); add("$n ounces")
        if ('0' in n.toString()) add(n.toString().replace('0', 'O') + "oz") // a zero read as the letter O
        add("${n}0z") // the letter o of "oz" read as a zero
    }

    @Test fun aMisreadBagSizeGivesWayToTheWeightPrintedOnTheLabel() {
        var checked = 0
        for (n in listOf(8, 10, 12, 16, 20, 32)) {
            val grams = oz(n)
            for (spelled in ozSpellings(n)) {
                // The gram figure beside it, read cleanly, with its "g" read as a 9, or missing.
                for (companion in listOf(" ($grams g)", " (${grams}9)", "")) {
                    val label = "SINGLE ORIGIN\nNet wt. $spelled$companion | Whole bean\nRoasted in small batches"
                    for (misread in listOf(grams * 10 + 9, 78, grams / 3, grams * 2 + 50)) {
                        assertEquals("$misread on \"$label\"", grams, NetWeight.reconcile(misread, label))
                        checked++
                    }
                }
            }
        }
        for (n in listOf(1, 2, 5)) {
            for (spelled in listOf("$n lb", "${n}LB", "$n lbs", "$n pounds")) {
                assertEquals(spelled, lb(n), NetWeight.reconcile(lb(n) * 10 + 9, "NET WEIGHT $spelled"))
                checked++
            }
        }
        assertEquals(1000, NetWeight.reconcile(100, "Net 1 kg whole bean"))
        assert(checked > 200) { "only $checked cases generated" }
    }

    @Test fun aBagSizeThatAgreesWithTheLabelOrHasNothingToCheckAgainstIsKept() {
        val cases = buildList {
            for (n in listOf(8, 10, 12, 16)) {
                add(oz(n) to "Net wt. $n oz (${oz(n)} g)")
                add(oz(n) + 3 to "NET WT ${n}OZ")
                add(oz(n) - 4 to "Net wt. ${n}oz")
            }
            add(250 to "250g")
            add(250 to "Net weight 250 g · whole bean")
            add(1000 to "1kg bag")
            add(907 to "2 lb")
            // The label says nothing about weight.
            add(340 to "Washed Caturra, notes of plum and cocoa")
            add(340 to "")
            // Two different weights and no net line: nothing to say which one is this bag.
            add(340 to "Also in 5 lb bags\nTry our 8 oz sampler")
        }
        for ((model, label) in cases) assertEquals("$model on \"$label\"", model, NetWeight.reconcile(model, label))
    }

    @Test fun aBlankBagSizeIsNeverFilledFromTheLabel() {
        assertNull(NetWeight.reconcile(null, "Net wt. 12 oz (340 g)"))
    }

    @Test fun theLabelGuessReadsTheSameSlips() {
        assertEquals(oz(10), NetWeight.fromLabel("Net wt. 1Ooz"))
        assertEquals(oz(12), NetWeight.fromLabel("NET WT 12OZ"))
        assertEquals(340, NetWeight.fromLabel("NET WT 12 OZ (340 g)"))
        assertNull(NetWeight.fromLabel("1 oz"))
    }
}
