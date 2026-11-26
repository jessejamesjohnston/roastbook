package dev.roastbook.app.util

import android.content.res.Resources
import androidx.compose.runtime.Composable
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

    /** Plural resource [id] for [count]; filled with [args], or with [count] when none are given. */
    fun plural(res: Resources, id: Int, count: Int, vararg args: Any?): String =
        format(res.getQuantityString(id, count), *(if (args.isEmpty()) arrayOf(count) else args))

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
fun englishPlural(id: Int, count: Int, vararg args: Any?): String = English.plural(LocalResources.current, id, count, *args)
