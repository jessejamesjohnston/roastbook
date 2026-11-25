package dev.roastbook.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release versions compare number by number, with or without a leading v. */
class AppVersionTest {
    @Test fun aLaterPatchMinorOrMajorIsNewer() {
        assertTrue(AppVersion.isNewer("0.3.6", "0.3.5")!!)
        assertTrue(AppVersion.isNewer("0.4.0", "0.3.5")!!)
        assertTrue(AppVersion.isNewer("1.0.0", "0.99.99")!!)
    }

    @Test fun partsCompareAsNumbersNotText() {
        assertTrue(AppVersion.isNewer("0.10.0", "0.9.0")!!)
        assertFalse(AppVersion.isNewer("0.9.0", "0.10.0")!!)
    }

    @Test fun theSameVersionOrAnOlderOneIsNotNewer() {
        assertFalse(AppVersion.isNewer("0.3.5", "0.3.5")!!)
        assertFalse(AppVersion.isNewer("v0.3.4", "0.3.5")!!)
    }

    @Test fun aTagMayCarryAVAndMissingPartsCountAsZero() {
        assertFalse(AppVersion.isNewer("v1.0", "1.0.0")!!)
        assertTrue(AppVersion.isNewer("V1.0.1", "1.0")!!)
        assertEquals(listOf(0, 3, 5), AppVersion.parse(" v0.3.5 "))
    }

    @Test fun aPreReleaseSuffixDoesNotChangeTheOrder() {
        assertEquals(listOf(0, 4, 0), AppVersion.parse("v0.4.0-beta1"))
        assertTrue(AppVersion.isNewer("v0.4.0-beta1", "0.3.5")!!)
    }

    @Test fun somethingThatIsNotAVersionCannotBeCompared() {
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse("1..2"))
        assertNull(AppVersion.isNewer("nightly", "0.3.5"))
        assertNull(AppVersion.isNewer("0.4.0", "unknown"))
    }

    @Test fun theDisplayedVersionDropsTheV() {
        assertEquals("0.4.0", AppVersion.display("v0.4.0"))
        assertEquals("0.4.0", AppVersion.display("0.4.0"))
    }
}
