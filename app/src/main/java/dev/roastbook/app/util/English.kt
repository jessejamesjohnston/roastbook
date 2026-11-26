package dev.roastbook.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Numbers, dates and times as they read inside the app's sentences, which are English only. Android
 * would format them for the device's language (a German month, Arabic-Indic digits) and mix two
 * languages in one line, so every value that lands in a sentence or label goes through here instead.
 * EnglishFormattingTest fails the build when a sentence with a number is formatted any other way.
 */
object English {
    private val LOCALE: Locale = Locale.US
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", LOCALE)
    private val SHORT_DATE = DateTimeFormatter.ofPattern("MMM d", LOCALE)
    private val LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", LOCALE)
    private val DATE_TIME = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", LOCALE)
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", LOCALE)

    /** [pattern] filled with [args], digits and decimal marks in English. */
    fun format(pattern: String, vararg args: Any?): String = String.format(LOCALE, pattern, *args)

    /** String resource [id] filled with [args] in English. */
    fun string(res: Resources, id: Int, vararg args: Any?): String = format(res.getString(id), *args)

    /**
     * Plural resource [id] for [count], chosen by English rules (1 is "one", anything else "other"),
     * whatever the device's language picks; filled with [args], or with [count] when none are given.
     */
    fun plural(context: Context, id: Int, count: Int, vararg args: Any?): String =
        format(englishResources(context).getQuantityString(id, if (count == 1) 1 else 2), *(if (args.isEmpty()) arrayOf(count) else args))

    /** [context]'s resources read as an English device would read them, so plural choice follows English rules. */
    @SuppressLint("AppBundleLocaleChanges") // Only picks which English plural form to read; the app ships English resources alone.
    private fun englishResources(context: Context): Resources {
        val config = Configuration(context.resources.configuration).apply { setLocale(LOCALE) }
        return context.createConfigurationContext(config).resources
    }

    /**
     * A file size as Android writes it in English ("2.6 GB", "0.99 GB", "836 MB"): steps of 1000, the
     * next unit once a figure passes 900, two decimals under 1, one under 10, none above.
     */
    fun fileSize(bytes: Long): String {
        val units = listOf("B", "kB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble()
        var unit = 0
        while (value > 900 && unit < units.lastIndex) { value /= 1000; unit++ }
        val pattern = when {
            unit == 0 -> "%.0f"
            value < 1 -> "%.2f"
            value < 10 -> "%.1f"
            else -> "%.0f"
        }
        return "${format(pattern, value)} ${units[unit]}"
    }

    /** A score to one decimal place: "4.3". */
    fun score(value: Float): String = format("%.1f", value)

    /** "3:04 PM" today; "3:04 PM on Nov 27" on any other day, so a time tomorrow never reads as today. */
    fun clock(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val then = Instant.ofEpochMilli(at).atZone(zone)
        val time = CLOCK.format(then)
        return if (then.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) time else "$time on ${SHORT_DATE.format(then)}"
    }

    /** "Nov 26". */
    fun shortDate(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = SHORT_DATE.format(Instant.ofEpochMilli(at).atZone(zone))

    /** "November 26, 2026". */
    fun longDate(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = LONG_DATE.format(Instant.ofEpochMilli(at).atZone(zone))

    /** "Nov 26, 2026". */
    fun mediumDate(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = MONTH_YEAR.format(Instant.ofEpochMilli(at).atZone(zone))

    /** "Nov 26, 2026 at 3:04 PM". */
    fun dateTime(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = DATE_TIME.format(Instant.ofEpochMilli(at).atZone(zone))
}

/** [English.string] for Compose; recomposes when the configuration changes. */
@Composable
fun englishString(id: Int, vararg args: Any?): String = English.string(LocalResources.current, id, *args)

/** [English.plural] for Compose; recomposes when the configuration changes. */
@Composable
fun englishPlural(id: Int, count: Int, vararg args: Any?): String {
    // Read so the text follows a configuration change, as stringResource does.
    LocalResources.current
    return English.plural(LocalContext.current, id, count, *args)
}
