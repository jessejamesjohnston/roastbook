package dev.roastbook.app.util

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * The price a user typed, shown in their device's currency format and read back whatever
 * separators they used. The stored number carries no currency; it is whatever the user paid.
 */
object Price {
    /** "$21.50" in en-US, "21,50 €" in de-DE; a plain two-decimal number where the locale has no currency. */
    fun format(value: Float, locale: Locale = Locale.getDefault()): String {
        val hasCurrency = runCatching { Currency.getInstance(locale) }.isSuccess
        val f = if (hasCurrency) NumberFormat.getCurrencyInstance(locale) else NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }
        return f.format(value.toDouble())
    }

    /**
     * Reads "21.50", "21,50", "$21.50", "21,50 €", "1,234.56", "1.234,56", "٢١٫٥٠", "21.500 KWD".
     * Only the number itself counts (a currency symbol with dots in it, such as "د.م.", is ignored);
     * digits of any script are read as digits. The last '.' or ',' (or the Arabic decimal mark) is
     * the decimal point when it is followed by up to [maxDecimals] digits, which is two, or the
     * currency's own number of decimals when it has more (three for the Kuwaiti dinar); any other
     * separator is grouping. Returns null when there are no digits.
     */
    fun parse(text: String?, locale: Locale = Locale.getDefault()): Float? {
        if (text == null) return null
        val first = text.indexOfFirst { it.isDigit() }
        if (first < 0) return null
        val last = text.indexOfLast { it.isDigit() }
        val number = text.substring(first, last + 1).mapNotNull { c ->
            when {
                c.isDigit() -> Character.forDigit(Character.digit(c, 10), 10)
                c == '.' || c == ',' || c == ARABIC_DECIMAL -> c
                c == ARABIC_GROUPING -> ','
                else -> null
            }
        }.joinToString("")
        val sep = number.indexOfLast { it == '.' || it == ',' || it == ARABIC_DECIMAL }
        val decimals = if (sep >= 0) number.length - sep - 1 else -1
        val decimalMark = sep >= 0 && (number[sep] == ARABIC_DECIMAL || decimals in 1..maxDecimals(locale))
        val normalized = if (decimalMark) {
            number.substring(0, sep).filter(Char::isDigit) + "." + number.substring(sep + 1)
        } else {
            number.filter(Char::isDigit)
        }
        return normalized.toFloatOrNull()
    }

    private const val ARABIC_DECIMAL = '\u066B'
    private const val ARABIC_GROUPING = '\u066C'

    private fun maxDecimals(locale: Locale): Int =
        maxOf(2, runCatching { Currency.getInstance(locale).defaultFractionDigits }.getOrDefault(2))
}
