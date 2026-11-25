package dev.roastbook.app.ui.add

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraLensTest {
    @Test fun theBackCameraWhenThereIsOneEvenWithAFrontOne() {
        assertEquals(Lens.BACK, chooseLens(hasBack = true, hasFront = true))
        assertEquals(Lens.BACK, chooseLens(hasBack = true, hasFront = false))
    }

    @Test fun aDeviceWithOnlyAFrontCameraUsesIt() {
        assertEquals(Lens.FRONT, chooseLens(hasBack = false, hasFront = true))
    }

    @Test fun noCameraAtAllMeansNone() {
        assertNull(chooseLens(hasBack = false, hasFront = false))
    }
}
