package dev.roastbook.app.ui.nav

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneRulesTest {
    @Test fun phonePortraitIsOnePane() = assertFalse(PaneRules.split(412.dp, 915.dp, null, null).twoPane)

    @Test fun phoneOnItsSideStaysOnePane() = assertFalse(PaneRules.split(780.dp, 360.dp, null, null).twoPane)

    @Test fun expandedWidthIsTwoPanesEvenWhenShort() = assertTrue(PaneRules.split(915.dp, 412.dp, null, null).twoPane)

    @Test fun tabletLandscapeSplitsInHalf() {
        val s = PaneRules.split(1280.dp, 800.dp, null, null)
        assertTrue(s.twoPane)
        assertEquals(640.dp, s.listWidth)
        assertEquals(0.dp, s.gap)
    }

    @Test fun tabletPortraitIsOnePane() = assertFalse(PaneRules.split(800.dp, 1280.dp, null, null).twoPane)

    @Test fun smallLandscapeTabletIsTwoPanes() = assertTrue(PaneRules.split(700.dp, 600.dp, null, null).twoPane)

    @Test fun foldSplitsAtTheHinge() {
        // An unfolded book-style foldable held portrait: narrower than expanded, but the fold decides.
        val s = PaneRules.split(673.dp, 841.dp, hingeLeft = 336.dp, hingeRight = 337.dp)
        assertTrue(s.twoPane)
        assertEquals(336.dp, s.listWidth)
        assertEquals(1.dp, s.gap)
    }

    @Test fun hingeAtTheEdgeIsIgnored() = assertFalse(PaneRules.split(673.dp, 841.dp, hingeLeft = 0.dp, hingeRight = 0.dp).twoPane)

    @Test fun coverScreenIsOnePane() = assertFalse(PaneRules.split(360.dp, 800.dp, null, null).twoPane)

    @Test fun thePaneClosesOnlyWhenItsCoffeeIsGone() {
        assertTrue("still loading", PaneRules.keepSelection(null))
        assertTrue(PaneRules.keepSelection(true))
        assertFalse("removed by a restore or a delete", PaneRules.keepSelection(false))
    }
}
