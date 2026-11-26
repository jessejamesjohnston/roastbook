package dev.roastbook.app.util

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Values inside the app's sentences read in English whatever the device's language. */
class EnglishTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    /** Devices whose digits, month names or day-half markers differ from English. */
    private val devices = listOf(Locale.US, Locale.forLanguageTag("ar-EG"), Locale.JAPAN, Locale.forLanguageTag("fa-IR"), Locale.forLanguageTag("hi-IN"), Locale.GERMANY)

    private fun everywhere(check: () -> Unit) {
        val saved = Locale.getDefault()
        try { for (l in devices) { Locale.setDefault(l); check() } } finally { Locale.setDefault(saved) }
    }

    @Test fun datesAndTimesAreEnglish() = everywhere {
        val afternoon = at(2026, 3, 9, 15, 4)
        assertEquals("Mar 9", English.shortDate(afternoon, zone))
        assertEquals("March 9, 2026", English.longDate(afternoon, zone))
        assertEquals("Mar 9, 2026", English.mediumDate(afternoon, zone))
        assertEquals("Mar 9, 2026 at 3:04 PM", English.dateTime(afternoon, zone))
    }

    @Test fun aRetryTimeCarriesItsDayWhenItIsNotToday() = everywhere {
        val now = at(2026, 11, 26, 23, 59)
        assertEquals("11:59 PM", English.clock(now, now, zone))
        assertEquals("12:01 AM on Nov 27", English.clock(at(2026, 11, 27, 0, 1), now, zone))
    }

    @Test fun numbersAndScoresUseEnglishDigits() = everywhere {
        assertEquals("4.3", English.score(4.33f))
        assertEquals("Photo 2 of 12", English.format("Photo %1\$d of %2\$d", 2, 12))
    }
}
