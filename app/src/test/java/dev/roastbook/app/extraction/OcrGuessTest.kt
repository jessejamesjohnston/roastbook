package dev.roastbook.app.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One or more tests per rule of the label-text guess. All bag text here is invented. */
class OcrGuessTest {
    private fun front(vararg lines: String) = listOf(OcrPage("photo 1 (front)", false, lines.joinToString("\n")))
    private fun lines(vararg l: String) = l.toList()

    // ---- roaster ----

    @Test fun roasterFromALineThatSaysRoasters() {
        assertEquals("Stillwick Roasters", OcrGuess.guess(front("ETHIOPIA", "STILLWICK ROASTERS", "Natural")).roaster)
    }

    @Test fun roasterJoinsABrandLineWithARoastersLineBelowIt() {
        assertEquals("Larkmoor Coffee Roasters", OcrGuess.guess(front("LARKMOOR", "COFFEE ROASTERS", "Kenya")).roaster)
    }

    @Test fun roasterFromTheFirstBrandLikeLineOfTheFront() {
        assertEquals("Northwind", OcrGuess.guess(front("NORTHWIND", "Finca Veleta", "Colombia")).roaster)
    }

    @Test fun roasterPrefersALineTheWebAddressConfirms() {
        // An invented address: the guess only trusts common top-level domains, so .example would not do.
        val f = OcrGuess.guess(front("FRESH ROASTED", "Beacon", "Sable & Rye", "sableandrye.coffee"))
        assertEquals("Sable & Rye", f.roaster)
    }

    @Test fun roasterIgnoresSpecLines() {
        assertNull(OcrGuess.guess(front("WHOLE BEAN", "12 OZ", "LIGHT ROAST")).roaster)
        assertNull(OcrGuess.guess(front("ETHIOPIA", "GESHA")).roaster)
    }

    @Test fun roasterIsNotTakenFromTheSellerScreenshot() {
        val pages = listOf(
            OcrPage("photo 1 (front)", false, "12 OZ\nWHOLE BEAN"),
            OcrPage("photo 2 (seller)", true, "Stillwick Roasters\nThis week's coffee"),
        )
        assertNull(OcrGuess.guess(pages).roaster)
    }

    // ---- origin ----

    @Test fun originIsAKnownCountry() {
        assertEquals("Colombia", OcrGuess.guess(front("Huila, Colombia")).originCountry)
        assertEquals("Peru", OcrGuess.guess(front("Cajamarca, PERÚ")).originCountry)
        assertEquals("Costa Rica", OcrGuess.guess(front("West Valley, Costa Rica")).originCountry)
    }

    @Test fun originPrefersTheMostMentionedCountry() {
        assertEquals("Kenya", OcrGuess.country("Grown in Kenya. Kenya's Nyeri county. Roasted like an Ethiopia natural"))
    }

    @Test fun originFromALabeledLineWins() {
        assertEquals("Rwanda", OcrGuess.guess(front("Blend of Brazil and more", "Origin: Rwanda")).originCountry)
    }

    @Test fun originIslandsMapToTheirCountry() {
        assertEquals("Indonesia", OcrGuess.country("Sumatra Aceh"))
    }

    // ---- process ----

    @Test fun processFromALabeledLine() {
        assertEquals("Anaerobic natural", OcrGuess.process(lines("Process: Anaerobic Natural")))
        assertEquals("Honey", OcrGuess.process(lines("PROCESS HONEY")))
        assertEquals("Red honey", OcrGuess.process(lines("Process - red honey")))
    }

    @Test fun processFromKeywords() {
        assertEquals("Washed", OcrGuess.process(lines("FULLY WASHED")))
        assertEquals("Natural", OcrGuess.process(lines("Natural process")))
        assertEquals("Wet-hulled", OcrGuess.process(lines("Giling basah")))
        assertEquals("Carbonic maceration", OcrGuess.process(lines("Carbonic Maceration")))
    }

    @Test fun honeyInTheTastingNotesIsNotAProcess() {
        assertNull(OcrGuess.process(lines("Tasting notes: honey, lemon")))
        assertNull(OcrGuess.process(lines("Sweet like honey")))
    }

    @Test fun naturalInAsentenceIsNotAProcess() {
        assertNull(OcrGuess.process(lines("A naturally sweet cup that we think you will love every single morning")))
    }

    // ---- elevation ----

    @Test fun elevationWithUnits() {
        assertEquals(1800, OcrGuess.elevation(lines("1,800 masl")))
        assertEquals(1800, OcrGuess.elevation(lines("Grown at 1800m")))
        assertEquals(1950, OcrGuess.elevation(lines("1.950 M.A.S.L.")))
    }

    @Test fun elevationRangeIsTheMidpoint() {
        assertEquals(1800, OcrGuess.elevation(lines("1,700-1,900 masl")))
        assertEquals(1650, OcrGuess.elevation(lines("1500 to 1800 meters")))
    }

    @Test fun elevationInFeetIsConverted() {
        assertEquals(1829, OcrGuess.elevation(lines("6,000 ft")))
    }

    @Test fun elevationFromALabelWithoutUnits() {
        assertEquals(1850, OcrGuess.elevation(lines("Altitude: 1850")))
    }

    @Test fun implausibleElevationsAreIgnored() {
        assertNull(OcrGuess.elevation(lines("250 m from the river")))
        assertNull(OcrGuess.elevation(lines("Est. 2012")))
    }

    // ---- varietals ----

    @Test fun varietalsFromTheKnownList() {
        assertEquals(listOf("Caturra", "Castillo"), OcrGuess.varietals("Varieties: Caturra, Castillo"))
        assertEquals(listOf("SL28", "SL34", "Ruiru 11"), OcrGuess.varietals("SL-28, SL34 & Ruiru 11"))
        assertEquals(listOf("Gesha"), OcrGuess.varietals("GEISHA"))
    }

    @Test fun longerVarietalNamesWinOverTheirParts() {
        assertEquals(listOf("Pink Bourbon"), OcrGuess.varietals("Pink Bourbon"))
    }

    @Test fun flavorsAreNotVarietals() {
        assertTrue(OcrGuess.varietals("Notes of cherry, cola and brown sugar").isEmpty())
    }

    // ---- tasting notes ----

    @Test fun notesOnlyFromAnExplicitNotesLine() {
        assertEquals(listOf("Cherry", "Cola", "Brown Sugar"), OcrGuess.tastingNotes(lines("Tasting notes: Cherry, Cola & Brown Sugar")))
        assertEquals(listOf("Peach", "Jasmine"), OcrGuess.tastingNotes(lines("WE TASTE:", "peach / jasmine")))
        assertEquals(listOf("Blackcurrant", "Grapefruit", "Cane Sugar"), OcrGuess.tastingNotes(lines("Tasting notes:", "blackcurrant, grapefruit,", "cane sugar", "Light roast")))
    }

    @Test fun taglinesAreNotNotes() {
        assertTrue(OcrGuess.tastingNotes(lines("Bright, juicy and full of life", "Roasted with care")).isEmpty())
        assertTrue(OcrGuess.tastingNotes(lines("Notes from the farm are on the back")).isEmpty())
    }

    // ---- region, producer, roast, weight ----

    @Test fun regionAndProducerOnlyFromLabels() {
        val f = OcrGuess.guess(front("Region: Nyeri", "Producer: Bluegate Growers Cooperative"))
        assertEquals("Nyeri", f.region)
        assertEquals("Bluegate Growers Cooperative", f.producer)
        assertNull(OcrGuess.guess(front("A lovely region to visit")).region)
    }

    @Test fun roastLevel() {
        assertEquals("Light", OcrGuess.roastLevel("LIGHT ROAST"))
        assertEquals("Medium-light", OcrGuess.roastLevel("Medium light roast"))
        assertNull(OcrGuess.roastLevel("light and bright"))
    }

    @Test fun bagSizeInGramsOuncesAndPounds() {
        assertEquals(340, OcrGuess.bagSize("NET WT 12 OZ (340 g)"))
        assertEquals(255, OcrGuess.bagSize("9OZ (255G)"))
        assertEquals(340, OcrGuess.bagSize("NET WT 12OZ"))
        assertEquals(907, OcrGuess.bagSize("2 lb"))
        assertEquals(1000, OcrGuess.bagSize("1 kg"))
    }

    // ---- the whole guess ----

    @Test fun theNameIsNeverGuessed() {
        assertNull(OcrGuess.guess(front("STILLWICK ROASTERS", "FINCA VELETA", "Colombia")).coffeeName)
    }

    @Test fun storedTextIsSplitBackIntoPages() {
        val stored = "--- photo 1 (front) ---\nNORTHWIND\nKenya\n\n--- photo 2 (seller) ---\nLarkmoor Roasters Club"
        val f = OcrGuess.guess(stored)
        assertEquals("Northwind", f.roaster)
        assertEquals("Kenya", f.originCountry)
    }

    @Test fun emptyTextGuessesNothing() {
        assertTrue(OcrGuess.guess("").isEmpty)
        assertEquals(0, OcrGuess.count(OcrGuess.guess("   ")))
    }

    // ---- values a rule recognizes but cannot use ----

    @Test fun aLabeledProcessNamingNoKnownMethodIsNotGuessed() {
        assertNull(OcrGuess.process(lines("Process: Experimental")))
    }

    @Test fun anAltitudeOutsideWhereCoffeeGrowsIsNotGuessed() {
        assertNull(OcrGuess.elevation(lines("Altitude: 50")))
        assertNull(OcrGuess.elevation(lines("Elevation: 9000")))
    }

    @Test fun aWeightOutsideABagsRangeIsNotGuessed() {
        assertNull(OcrGuess.bagSize("10 g sample"))
        assertNull(OcrGuess.bagSize("1 oz"))
        assertNull(OcrGuess.bagSize("9 kg"))
        assertNull(OcrGuess.bagSize("20 lb"))
    }

    @Test fun anImplausibleGramFigureFallsThroughToTheOunceFigure() {
        assertEquals(340, OcrGuess.bagSize("Lot 12 g / 12 oz"))
    }
}
