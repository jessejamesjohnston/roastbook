package dev.roastbook.app.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cleanup every reading passes before it is saved: placeholder words, JSON residue, dates. */
class FieldCleanupTest {
    @Test fun placeholderWordsAloneAreEmptyInAnyCaseQuotedOrRepeated() {
        val placeholders = listOf(
            "null", "NULL", "null null", "Null, null", "\"null\"", "'null'", "none", "None.", "n/a", "N/A",
            "unknown", "Unknown unknown", "undefined", "[null]", "{null}", "null,", "\"null\",", "  null  ", "null / none",
            "-", "—", "\",", "[]", "{}", "\"\"",
        )
        for (p in placeholders) assertNull("<$p>", FieldCleanup.text(p))
    }

    @Test fun aRealValueThatContainsAPlaceholderWordStays() {
        for (v in listOf("Unknown Pleasures Blend", "None Such Roasters", "Null Point Coffee", "Northwind n/a Lot 4")) {
            assertEquals(v, FieldCleanup.text(v))
        }
    }

    @Test fun jsonResidueAtEitherEndIsTrimmed() {
        val table = mapOf(
            "Finca Veleta\"," to "Finca Veleta",
            "\"Finca Veleta" to "Finca Veleta",
            "Finca Veleta\"}" to "Finca Veleta",
            "{\"Finca Veleta" to "Finca Veleta",
            "Lantern Roasters,," to "Lantern Roasters",
            ", Lantern Roasters" to "Lantern Roasters",
            "Plum]" to "Plum",
            "[Plum" to "Plum",
            "Washed\"]," to "Washed",
            "Washed}" to "Washed",
        )
        for ((raw, want) in table) assertEquals("<$raw>", want, FieldCleanup.text(raw))
    }

    @Test fun legitimatePunctuationIsKept() {
        val kept = listOf(
            "Signal Roasters Co.",
            "O'Brien Estate",
            "\"Geisha\"",
            "Lot \"A\" Natural",
            "[Cherry, Plum, Cocoa]",
            "Bright, sweet, and juicy.",
            "Northwind (Lot 7)",
            "Salt & Pepper",
        )
        for (v in kept) assertEquals(v, FieldCleanup.text(v))
        // Residue around a value quoted on purpose goes; the quotes it was printed with stay.
        assertEquals("\"Geisha\"", FieldCleanup.text("\"Geisha\","))
    }

    @Test fun cleaningTwiceChangesNothing() {
        for (v in listOf("Finca Veleta\",", "\"Geisha\",", "[Plum", "null null", "Signal Roasters Co.")) {
            assertEquals(FieldCleanup.text(v), FieldCleanup.text(FieldCleanup.text(v)))
        }
    }

    /** Roast dates as bags stamp them: every style with a digit is kept as written. */
    @Test fun everyRoastDateWithADigitIsKeptAsWritten() {
        val dates = listOf(
            "03NOV26", "03 NOV 26", "11.03.26", "11/03/2026", "2026-11-03", "2026/11/03", "Nov 3", "3 nov. 2026",
            "3. November 2026", "08/02", "KW 45", "ROASTED 03NOV26", "Roasted on Sept. 14, 2026", "12 März 2026", "Lot 26312",
        )
        for (d in dates) assertEquals("<$d>", d, FieldCleanup.date(d))
        // Residue around a date goes; the date stays.
        assertEquals("03NOV26", FieldCleanup.date("03NOV26\","))
        assertEquals("11.03.26", FieldCleanup.date("  11.03.26 "))
    }

    @Test fun aRoastDateIsEmptiedOnlyWhenItIsJunkOrHasNoDigit() {
        for (d in listOf("null null", "null", "unknown", "N/A", ",", "\",", "[]", "-", "roasted fresh", "fresh", "")) {
            assertNull("<$d>", FieldCleanup.date(d))
        }
    }

    @Test fun everyTextFieldAndListIsCleaned() {
        val raw = BagFacts(
            roaster = "Lantern Roasters\",", coffeeName = "null null", originCountry = "N/A", region = "[Huila",
            producer = "undefined", process = "Washed}", varietal = listOf("Caturra\"", "null", "Caturra"),
            elevationM = 1520, roastLevel = "\"unknown\"", roastDate = "null null", bagSizeG = 340,
            tastingNotes = listOf("Plum]", "none", "Cocoa"), bagDescription = "{", sellerDescription = "Bright and sweet.",
        )
        val want = BagFacts(
            roaster = "Lantern Roasters", region = "Huila", process = "Washed", varietal = listOf("Caturra"),
            elevationM = 1520, bagSizeG = 340, tastingNotes = listOf("Plum", "Cocoa"), sellerDescription = "Bright and sweet.",
        )
        assertEquals(want, FieldCleanup.clean(raw))
    }

    @Test fun theParserHandsBackCleanedFactsSoQualityChecksSeeThem() {
        val parsed = BagFactsParser.parseChecked("""{"roaster": "null null", "coffee_name": "Finca Veleta\",", "roast_date": "null null"}""")!!
        assertNull(parsed.facts.roaster)
        assertEquals("Finca Veleta", parsed.facts.coffeeName)
        assertNull(parsed.facts.roastDate)
        assertEquals(1, parsed.facts.fieldCount)
        // A reading whose only "names" are placeholders is thin.
        assert(BagFactsParser.parseChecked("""{"roaster": "null", "coffee_name": "null null", "process": "Washed"}""")!!.facts.isThin)
    }
}
