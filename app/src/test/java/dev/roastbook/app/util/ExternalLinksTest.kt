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

    @Test fun aNotificationsPageOpensInTheBrowserByTheSameIntent() {
        val i = ExternalLinks.browserIntent(page)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(page, i.data)
        assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test fun emailOpensTheMailAppWithTheAddressAndSubject() {
        assertTrue(ExternalLinks.email(app, "hello@example.com", "Roastbook 1.0"))
        val i = opened()
        assertEquals(Intent.ACTION_SENDTO, i.action)
        assertEquals("mailto:", i.dataString)
        assertEquals(listOf("hello@example.com"), i.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.toList())
        assertEquals("Roastbook 1.0", i.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test fun withNoMailAppTheCallerIsTold() {
        shadowOf(app).checkActivities(true)
        assertFalse(ExternalLinks.email(app, "hello@example.com", "x"))
    }

    /** Pages and email leave the app only through this module: no view or send intent is built elsewhere. */
    @Test fun noOtherCodeOpensAPageOrAnEmailItself() {
        val src = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val hits = src.walk().filter { it.extension == "kt" && it.name != "ExternalLinks.kt" }
            .flatMap { f -> listOf("ACTION_VIEW", "ACTION_SENDTO").filter { it in f.readText() }.map { "${f.name}: $it" } }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
