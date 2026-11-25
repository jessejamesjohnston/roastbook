package dev.roastbook.app.ui.theme

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.data.prefs.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app's own night mode and per-app language, as the system services see them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppearanceSystemTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val uiModes get() = context.getSystemService(UiModeManager::class.java)
    private val locales get() = context.getSystemService(LocaleManager::class.java)

    @Test fun eachThemeChoiceSetsTheAppsNightMode() {
        NightMode.apply(context, ThemeMode.DARK)
        assertEquals(UiModeManager.MODE_NIGHT_YES, shadowOf(uiModes).applicationNightMode)
        NightMode.apply(context, ThemeMode.LIGHT)
        assertEquals(UiModeManager.MODE_NIGHT_NO, shadowOf(uiModes).applicationNightMode)
        NightMode.apply(context, ThemeMode.SYSTEM)
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, shadowOf(uiModes).applicationNightMode)
    }

    @Test fun perAppLanguageIsOfferedFromAndroid13() {
        assertTrue(AppLanguage.available)
    }

    @Test fun withNoAppLanguageTheAppFollowsTheDevice() {
        assertNull(AppLanguage.current(context))
    }

    @Test fun aChosenLanguageIsStoredForTheAppAndCanBeCleared() {
        AppLanguage.set(context, "en")
        assertEquals("en", locales.applicationLocales[0].toLanguageTag())
        assertEquals("en", AppLanguage.current(context))
        AppLanguage.set(context, null)
        assertTrue(locales.applicationLocales.isEmpty)
        assertNull(AppLanguage.current(context))
    }

    @Test fun aLanguageIsNamedInItselfAndCapitalized() {
        assertEquals("English", AppLanguage.displayName("en"))
        // Spanish names itself in lower case; the list shows it capitalized.
        assertEquals("Español", AppLanguage.displayName("es"))
        assertEquals("Deutsch", AppLanguage.displayName("de"))
    }
}
