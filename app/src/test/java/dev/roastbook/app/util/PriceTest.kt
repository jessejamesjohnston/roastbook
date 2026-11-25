package dev.roastbook.app.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceTest {
    @Test fun readsDotDecimal() = assertEquals(21.5f, Price.parse("21.50"))
    @Test fun readsCommaDecimal() = assertEquals(21.5f, Price.parse("21,50"))

    @Test fun readsCurrencySymbols() {
        assertEquals(21.5f, Price.parse("$21.50"))
        assertEquals(21.5f, Price.parse("21,50 €"))
    }

    @Test fun readsGroupedThousands() {
        assertEquals(1234.56f, Price.parse("1,234.56"))
        assertEquals(1234.56f, Price.parse("1.234,56"))
        assertEquals(1234f, Price.parse("1.234"))
        assertEquals(1234f, Price.parse("1,234"))
    }

    @Test fun readsWholeAndSingleDecimal() {
        assertEquals(18f, Price.parse("18"))
        assertEquals(18.5f, Price.parse("18,5"))
    }

    @Test fun nothingToRead() {
        assertNull(Price.parse(""))
        assertNull(Price.parse("free"))
        assertNull(Price.parse(null))
    }

    @Test fun formatsInTheDeviceCurrency() {
        assertEquals("$21.50", Price.format(21.5f, Locale.US))
        // Which space a JDK puts before the euro sign varies; compare digits and symbol only.
        assertEquals("21,50€", Price.format(21.5f, Locale.GERMANY).filter { !it.isWhitespace() && it != ' ' && it != ' ' })
    }

    @Test fun readsOtherScriptsAndCurrenciesWithThreeDecimals() {
        assertEquals(21.5f, Price.parse("\u0662\u0661\u066B\u0665\u0660")!!, 0.001f) // Arabic-Indic digits and decimal mark
        assertEquals(21.5f, Price.parse("21,50 \u062F.\u0645.")!!, 0.001f) // a currency symbol with dots in it
        assertEquals(21.5f, Price.parse("KWD21.500", Locale.forLanguageTag("en-KW"))!!, 0.001f)
        assertEquals(1234f, Price.parse("1.234", Locale.US)) // two-decimal currency: still grouping
    }

    @Test fun roundTripsInThreeDecimalAndArabicLocales() {
        for (loc in listOf(Locale.forLanguageTag("ar-KW"), Locale.forLanguageTag("en-KW"), Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("ar-MA"), Locale.forLanguageTag("ar-TN"))) {
            assertEquals(loc.toString(), 21.5f, Price.parse(Price.format(21.5f, loc), loc)!!, 0.001f)
        }
    }

    @Test fun roundTripsAcrossLocales() {
        for (loc in listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale.UK)) {
            assertEquals(loc.toString(), 21.5f, Price.parse(Price.format(21.5f, loc))!!, 0.001f)
        }
    }

    @Test fun aLocaleWithNoCurrencyShowsTwoDecimals() {
        assertEquals("21.50", Price.format(21.5f, Locale.ENGLISH))
    }

    @Test fun formatUsesTheDeviceLocaleByDefault() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("$21.50", Price.format(21.5f))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun readsTheArabicThousandsSeparator() {
        assertEquals(1234.5f, Price.parse("\u0661\u066C\u0662\u0663\u0664\u066B\u0665"))
    }

    @Test fun spacesBetweenDigitGroupsAreIgnored() {
        assertEquals(1234.5f, Price.parse("1 234,50 \u20AC"))
        assertEquals(1234.5f, Price.parse("1\u202F234,50"))
    }
}
