package dev.roastbook.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Counts and file sizes inside the app's English sentences, on devices whose language picks
 * plurals differently (French counts 0 as one, Russian counts 21 as one) or writes other digits
 * (Arabic). English chooses "coffee" or "coffees" by English rules and writes English digits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EnglishResourcesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun countsAreEnglish() {
        assertEquals("0 coffees", English.plural(context, R.plurals.coffees, 0))
        assertEquals("1 coffee", English.plural(context, R.plurals.coffees, 1))
        assertEquals("2 coffees", English.plural(context, R.plurals.coffees, 2))
        assertEquals("21 coffees", English.plural(context, R.plurals.coffees, 21))
        assertEquals("101 coffees", English.plural(context, R.plurals.coffees, 101))
        assertEquals("Keeps the newest 3 backups in the folder", English.plural(context, R.plurals.settings_backup_keeps, 3))
    }

    private fun sizesAreEnglish() {
        assertEquals("2.6 GB", English.fileSize(2_600_000_000L))
        assertEquals("0.99 GB", English.fileSize(990_000_000L))
        assertEquals("836 MB", English.fileSize(836_000_000L))
        assertEquals("4.2 MB", English.fileSize(4_200_000L))
        assertEquals("17 MB", English.fileSize(17_000_000L))
        assertEquals("0 B", English.fileSize(0L))
        assertEquals("512 B", English.fileSize(512L))
        assertEquals("0.95 kB", English.fileSize(950L))
    }

    @Test @Config(qualifiers = "fr") fun onAFrenchDevice() { countsAreEnglish(); sizesAreEnglish() }
    @Test @Config(qualifiers = "ru") fun onARussianDevice() { countsAreEnglish(); sizesAreEnglish() }
    @Test @Config(qualifiers = "ar") fun onAnArabicDevice() { countsAreEnglish(); sizesAreEnglish() }
    @Test fun onAnEnglishDevice() { countsAreEnglish(); sizesAreEnglish() }
}
