package dev.roastbook.app.util

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Where a link that leaves the app opens: a browser tab on a laptop, an in-app tab elsewhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExternalLinksTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val page = Uri.parse("https://example.com/help")

    /** A browser that offers in-app tabs, as Chrome does on a phone. */
    private fun installTabBrowser() {
        val pm = shadowOf(app.packageManager)
        val browser = ComponentName("com.example.browser", "com.example.browser.Main")
        pm.addActivityIfNotPresent(browser)
        pm.addIntentFilterForActivity(
            browser,
            IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_BROWSABLE); addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("http"); addDataScheme("https") },
        )
        val service = ComponentName("com.example.browser", "com.example.browser.TabService")
        pm.addServiceIfNotPresent(service)
        pm.addIntentFilterForService(service, IntentFilter(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION))
    }

    private fun laptop() = shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_PC, true)

    private fun opened(): Intent = shadowOf(app).nextStartedActivity

    private val Intent.isInAppTab get() = hasExtra(CustomTabsIntent.EXTRA_SESSION)

    @Test fun theRuleInEveryCombination() {
        assertEquals(ExternalLinks.Way.IN_APP_TAB, ExternalLinks.way(laptop = false, inAppTabs = true))
        assertEquals(ExternalLinks.Way.BROWSER, ExternalLinks.way(laptop = false, inAppTabs = false))
        assertEquals(ExternalLinks.Way.BROWSER, ExternalLinks.way(laptop = true, inAppTabs = true))
        assertEquals(ExternalLinks.Way.BROWSER, ExternalLinks.way(laptop = true, inAppTabs = false))
    }

    @Test fun onAPhoneALinkOpensInAnInAppTab() {
        installTabBrowser()
        assertTrue(ExternalLinks.open(app, page))
        val i = opened()
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(page, i.data)
        assertTrue(i.isInAppTab)
    }

    @Test fun onALaptopALinkOpensAsANormalBrowserTab() {
        installTabBrowser()
        laptop()
        assertTrue(ExternalLinks.open(app, page))
        val i = opened()
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(page, i.data)
        assertFalse(i.isInAppTab)
    }

    @Test fun onAPhoneWithNoInAppTabsALinkFallsBackToTheBrowser() {
        assertTrue(ExternalLinks.open(app, page))
        val i = opened()
        assertEquals(page, i.data)
        assertFalse(i.isInAppTab)
    }

    @Test fun withNothingToOpenItTheCallerIsTold() {
        shadowOf(app).checkActivities(true)
        assertFalse(ExternalLinks.open(app, page))
    }
}
