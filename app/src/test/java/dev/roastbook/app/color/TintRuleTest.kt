package dev.roastbook.app.color

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TintRuleTest {
    private fun hex(argb: Int) = "#%06x".format(argb and 0xFFFFFF)

    @Test fun roundTripsThroughOklch() {
        for (c in listOf(0xFFAB7D56.toInt(), 0xFF9C9185.toInt(), 0xFFA97271.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val back = OklchConverter.toArgb(OklchConverter.fromArgb(c))
            for (shift in listOf(16, 8, 0)) {
                assertTrue(abs(((c shr shift) and 0xFF) - ((back shr shift) and 0xFF)) <= 1)
            }
        }
    }

    @Test fun vibrantBagKeepsItsHue() {
        // Bag A: vibrant #ab7d56 → the palette's light #f7dec9 and dark #3e2916 (±2 per channel).
        val r = TintRule.compute(vibrant = 0xFFAB7D56.toInt(), muted = null)
        assertFalse(r.fallback)
        assertClose(0xF7DEC9, r.tintLight)
        assertClose(0x3E2916, r.tintDark)
    }

    @Test fun whitePackagingFallsBackToParchment() {
        // Bag B: "vibrant" #9c9185 has chroma 0.022 → fallback hue 70°, light #efe0cf.
        val r = TintRule.compute(vibrant = 0xFF9C9185.toInt(), muted = null)
        assertTrue(r.fallback)
        assertClose(0xEFE0CF, r.tintLight)
    }

    @Test fun noSwatchesAtAllStillProducesColors() {
        val r = TintRule.compute(null, null)
        assertTrue(r.fallback)
        assertEquals(hex(TintRule.neutral.tintLight), hex(r.tintLight))
    }

    private fun assertClose(expectedRgb: Int, actualArgb: Int, tol: Int = 3) {
        for (shift in listOf(16, 8, 0)) {
            val e = (expectedRgb shr shift) and 0xFF; val a = (actualArgb shr shift) and 0xFF
            assertTrue("expected ${hex(expectedRgb)} got ${hex(actualArgb)}", abs(e - a) <= tol)
        }
    }
}
