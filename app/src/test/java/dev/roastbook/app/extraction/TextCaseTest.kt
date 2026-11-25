package dev.roastbook.app.extraction

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCaseTest {
    @Test fun shoutingNamesGetTitleCase() {
        assertEquals("Sable & Rye Coffee Roasters", TextCase.title("SABLE & RYE COFFEE ROASTERS"))
        assertEquals("Hills of Lumen", TextCase.title("HILLS OF LUMEN"))
        assertEquals("SL28", TextCase.title("SL28"))
    }
    @Test fun mixedCaseIsLeftAlone() {
        assertEquals("northwind.example", TextCase.title("northwind.example"))
        assertEquals("Finca Veleta", TextCase.title("Finca Veleta"))
    }
    @Test fun shoutingProseGetsSentenceCase() {
        assertEquals("Double fermented. Then dried on beds.", TextCase.sentence("DOUBLE FERMENTED. THEN DRIED ON BEDS."))
    }
    @Test fun blankIsNull() {
        assertEquals(null, TextCase.title("  "))
        assertEquals(null, TextCase.sentence(null))
    }
}
