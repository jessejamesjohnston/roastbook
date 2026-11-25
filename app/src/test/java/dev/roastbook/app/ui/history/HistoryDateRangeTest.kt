package dev.roastbook.app.ui.history

import androidx.paging.testing.asSnapshot
import dev.roastbook.app.data.db.DateRange
import dev.roastbook.app.data.db.HistoryFilter
import dev.roastbook.app.data.db.HistoryQuery
import dev.roastbook.app.data.db.HistorySort
import dev.roastbook.app.testutil.TestGraph
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The History "Logged" presets: where each one starts, and which coffees it keeps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HistoryDateRangeTest {
    private val day = 24L * 60 * 60 * 1000
    private fun at(year: Int, month: Int, dayOfMonth: Int, hour: Int = 0) =
        Calendar.getInstance().apply { clear(); set(year, month, dayOfMonth, hour, 0, 0) }.timeInMillis

    @Test fun anyDateHasNoFloor() = assertNull(HistoryQuery.dateFloor(DateRange.ANY, at(2026, Calendar.JUNE, 15)))

    @Test fun thirtyAndNinetyDaysCountBackFromNow() {
        val now = at(2026, Calendar.JUNE, 15, hour = 14)
        assertEquals(now - 30 * day, HistoryQuery.dateFloor(DateRange.DAYS_30, now))
        assertEquals(now - 90 * day, HistoryQuery.dateFloor(DateRange.DAYS_90, now))
    }

    @Test fun thisYearStartsAtMidnightOnTheFirstOfJanuary() {
        val now = at(2026, Calendar.JUNE, 15, hour = 14)
        assertEquals(at(2026, Calendar.JANUARY, 1), HistoryQuery.dateFloor(DateRange.THIS_YEAR, now))
    }

    @Test fun thisYearKeepsOnlyCoffeesLoggedSinceNewYear() = runBlocking {
        val g = TestGraph()
        try {
            val now = System.currentTimeMillis()
            val newYear = HistoryQuery.dateFloor(DateRange.THIS_YEAR, now)!!
            g.insertCoffee("new", at = newYear + 1_000) { copy(coffeeName = "Harbor Fog") }
            g.insertCoffee("old", at = newYear - 1_000) { copy(coffeeName = "Copper Kettle") }
            val rows = g.repo.history(HistorySort.RECENT, HistoryFilter(dateRange = DateRange.THIS_YEAR), descending = true).asSnapshot()
            assertEquals(listOf("new"), rows.map { it.id })
        } finally {
            g.close()
        }
    }
}
