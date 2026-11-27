package dev.roastbook.app.ui.detail

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The viewer's zoom: between 1x and 5x, a zoomed photo never pans past its edges, and 1x sits centered. */
class ZoomStateTest {
    private fun zoom() = ZoomState().apply { size(IntSize(1000, 800)) }

    @Test fun zoomStaysBetweenOneAndFiveTimes() {
        val z = zoom()
        repeat(20) { z.zoomBy(2f) }
        assertEquals(5f, z.scale, 0.001f)
        repeat(20) { z.zoomBy(0.5f) }
        assertEquals(1f, z.scale, 0.001f)
        assertFalse(z.zoomed)
    }

    @Test fun aZoomedPhotoPansOnlyAsFarAsItsEdges() {
        val z = zoom()
        z.zoomBy(2f)
        z.zoomBy(1f, pan = Offset(10_000f, -10_000f))
        assertEquals(500f, z.offset.x, 0.5f) // (1000 * (2 - 1)) / 2
        assertEquals(-400f, z.offset.y, 0.5f)
    }

    @Test fun backToOneTimesIsCenteredAndResetClearsEverything() {
        val z = zoom()
        z.zoomBy(3f, centroid = Offset(900f, 700f))
        assertTrue(z.zoomed)
        z.zoomBy(0.1f)
        assertEquals(Offset.Zero, z.offset)
        z.zoomBy(2.5f, centroid = Offset(100f, 100f))
        z.reset()
        assertEquals(1f, z.scale, 0.001f)
        assertEquals(Offset.Zero, z.offset)
    }
}
