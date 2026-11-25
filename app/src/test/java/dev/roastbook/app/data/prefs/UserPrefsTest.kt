package dev.roastbook.app.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every Settings choice outside backup is stored and read back, starting from the product defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UserPrefsTest {
    private fun prefs() = UserPrefs(PreferenceDataStoreFactory.create {
        File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "prefs-${UUID.randomUUID()}.preferences_pb")
    })

    @Test fun aFreshInstallHasTheProductDefaults() = runBlocking {
        assertEquals(UserSettings(), prefs().current())
    }

    @Test fun eachChoiceIsStoredAndReadBack() = runBlocking {
        val p = prefs()
        p.setSecondLookEnabled(false)
        p.setSecondLookDays(10)
        p.setSaveSquares(true)
        p.setOnboardingDone(true)
        p.setGeminiModel("  gemini-test-model ")
        p.setThemeMode(ThemeMode.DARK)
        p.setHighContrast(true)
        assertEquals(
            UserSettings(
                secondLookEnabled = false, secondLookDays = 10, saveSquaresToPhotos = true, onboardingDone = true,
                geminiModel = "gemini-test-model", themeMode = ThemeMode.DARK, highContrast = true,
            ),
            p.current(),
        )
    }

    @Test fun aBlankModelNameFallsBackToTheDefaultModel() = runBlocking {
        val p = prefs()
        p.setGeminiModel("   ")
        assertEquals(UserSettings.DEFAULT_GEMINI_MODEL, p.current().geminiModel)
    }

    @Test fun theSecondLookIntervalIsOneOfTheOfferedChoices() {
        assertTrue(UserSettings().secondLookDays in UserSettings.SECOND_LOOK_CHOICES)
    }
}
