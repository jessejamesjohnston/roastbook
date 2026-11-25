package dev.roastbook.app.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BagFactsParserTest {
    @Test
    fun parsesCleanJson() {
        val f = BagFactsParser.parse(
            """{"roaster": "Northwind Coffee Roasters", "coffee_name": "Finca Veleta", "origin_country": "COLOMBIA",
               "process": "HONEY", "varietal": ["CATURRA"], "elevation_m": 1520, "bag_size_g": 284,
               "tasting_notes": ["SOUR CANDY", "GUAVA JAM"], "bag_description": null, "region": null}"""
        )!!
        assertEquals("Northwind Coffee Roasters", f.roaster)
        assertEquals("Finca Veleta", f.coffeeName)
        assertEquals("COLOMBIA", f.originCountry)
        assertEquals("HONEY", f.process)
        assertEquals(listOf("CATURRA"), f.varietal)
        assertEquals(1520, f.elevationM)
        assertEquals(284, f.bagSizeG)
        assertEquals(listOf("SOUR CANDY", "GUAVA JAM"), f.tastingNotes)
        assertNull(f.bagDescription)
        assertNull(f.region)
    }

    @Test
    fun everyKeyLandsInItsOwnField() {
        val f = BagFactsParser.parse(
            """{"roaster": "Larkmoor", "coffee_name": "Copper Ridge", "origin_country": "Kenya", "region": "Nyeri",
               "producer": "Hillside Growers", "process": "Washed", "varietal": ["SL28", "SL34"], "elevation_m": 1800,
               "roast_level": "Light", "roast_date": "2026-05-01", "bag_size_g": 250, "tasting_notes": ["Blackcurrant"],
               "bag_description": "Bright and juicy.", "seller_description": "A seasonal lot."}"""
        )!!
        assertEquals(
            BagFacts(
                roaster = "Larkmoor", coffeeName = "Copper Ridge", originCountry = "Kenya", region = "Nyeri",
                producer = "Hillside Growers", process = "Washed", varietal = listOf("SL28", "SL34"), elevationM = 1800,
                roastLevel = "Light", roastDate = "2026-05-01", bagSizeG = 250, tastingNotes = listOf("Blackcurrant"),
                bagDescription = "Bright and juicy.", sellerDescription = "A seasonal lot.",
            ),
            f,
        )
    }

    @Test
    fun parsesJsonWrappedInFencesAndProse() {
        val f = BagFactsParser.parse("Sure! ```json\n{\"roaster\": \"Larkmoor Coffee\", \"bag_size_g\": 250.0}\n```")!!
        assertEquals("Larkmoor Coffee", f.roaster)
        assertEquals(250, f.bagSizeG)
    }

    @Test
    fun parsesYamlShapedDrift() {
        val raw = """
            roaster: STILLWICK ROASTERS
            coffee_name: HALF LIGHT
            origin_country: null
            producer: TEODORO BALLEN AND ROSALBA NÚÑEZ BALLEN
            varietal: [
              "SIDRA"
            ]
            elevation_m: 1680
            bag_size_g: 283.5
            tasting_notes: [ORANGE BLOSSOM, APRICOT, LYCHEE, HONEYDEW]
            bag_description: A winter release.
        """.trimIndent()
        val f = BagFactsParser.parse(raw)!!
        assertEquals("STILLWICK ROASTERS", f.roaster)
        assertEquals("HALF LIGHT", f.coffeeName)
        assertEquals("TEODORO BALLEN AND ROSALBA NÚÑEZ BALLEN", f.producer)
        assertNull(f.originCountry)
        assertEquals(listOf("SIDRA"), f.varietal)
        assertEquals(listOf("ORANGE BLOSSOM", "APRICOT", "LYCHEE", "HONEYDEW"), f.tastingNotes)
        assertEquals(284, f.bagSizeG)
        assertEquals("A winter release.", f.bagDescription)
    }

    @Test
    fun nullListsAndUnknownsBecomeEmpty() {
        val f = BagFactsParser.parse("""{"varietal": [null], "tasting_notes": null, "roaster": "unknown", "elevation_m": "1520 MASL"}""")!!
        assertEquals(emptyList<String>(), f.varietal)
        assertEquals(emptyList<String>(), f.tastingNotes)
        assertNull(f.roaster)
        assertEquals(1520, f.elevationM)
    }

    @Test
    fun rejectsImplausibleNumbers() {
        val f = BagFactsParser.parse("""{"elevation_m": 99999, "bag_size_g": -3}""")!!
        assertNull(f.elevationM)
        assertNull(f.bagSizeG)
    }

    @Test
    fun proseWithoutKeysIsNull() {
        assertNull(BagFactsParser.parse("I cannot read this image."))
        assertNull(BagFactsParser.parse(""))
        assertNotNull(BagFactsParser.parse("{}"))
    }

    // --- Lenient repair (the on-device reader's comma slips, measured on LiteRT-LM output) ---

    @Test
    fun repairsAMissingCommaBetweenEntries() {
        val f = BagFactsParser.parse("{\n  \"roaster\": \"Larkmoor\"\n  \"elevation_m\": 1900\n  \"varietal\": [\"Gesha\"]\n}")!!
        assertEquals("Larkmoor", f.roaster)
        assertEquals(1900, f.elevationM)
        assertEquals(listOf("Gesha"), f.varietal)
    }

    @Test
    fun repairsDoubledAndTrailingCommas() {
        val f = BagFactsParser.parse("{\"roaster\": \"Stillwick Roasters\",, \"bag_size_g\": 340,\n}")!!
        assertEquals("Stillwick Roasters", f.roaster)
        assertEquals(340, f.bagSizeG)
    }

    @Test
    fun repairsACommaOnItsOwnLineAndALeadingComma() {
        val f = BagFactsParser.parse("{\n\"roaster\": \"Northwind\"\n,\n, \"process\": \"Washed\"\n}")!!
        assertEquals("Northwind", f.roaster)
        assertEquals("Washed", f.process)
    }

    @Test
    fun repairLeavesValidJsonAlone() {
        val j = "{\"a\": \"x, y\", \"b\": [1, 2]}"
        assertEquals(j, BagFactsParser.repair(j))
    }

    @Test
    fun bracesAroundSomethingThatIsNotJsonAndNoKeysAreNothing() {
        assertNull(BagFactsParser.parse("{ this is not an answer }"))
    }

    @Test
    fun aListWhereANumberBelongsIsDropped() {
        val f = BagFactsParser.parse("""{"roaster": "Larkmoor", "elevation_m": [1200, 1400]}""")!!
        assertEquals("Larkmoor", f.roaster)
        assertNull(f.elevationM)
    }

    @Test
    fun aListGivenAsOneStringIsSplitOnBars() {
        assertEquals(listOf("Caturra", "Bourbon"), BagFactsParser.parse("""{"varietal": "Caturra | Bourbon"}""")!!.varietal)
        assertEquals(listOf("Gesha"), BagFactsParser.parse("""{"varietal": "Gesha"}""")!!.varietal)
    }
}
