package dev.roastbook.app.data.db

import androidx.sqlite.db.SimpleSQLiteQuery

/**
 * History sort orders: the coffee column each sorts on and its default direction. Rows still being
 * read stay on top and rows with no value sort last, whichever direction is chosen.
 */
enum class HistorySort(val column: String, val desc: Boolean) {
    RECENT("logged_at", true),
    OVERALL("overall", true),
    AROMA("aroma", true),
    FLAVOR("flavor", true),
    ACIDITY("acidity", true),
    BODY("body", true),
    FINISH("finish", true),
    PRICE("price_usd", true),
}

/** Logged-date presets; a real from/to picker is more UI than the question deserves. */
enum class DateRange { ANY, DAYS_30, DAYS_90, THIS_YEAR }

/**
 * The History filters; every one that is set must match. [search] is literal text matched anywhere
 * in the coffee's text fields.
 */
data class HistoryFilter(
    val search: String = "",
    val unratedOnly: Boolean = false,
    val buyAgainOnly: Boolean = false,
    val sourceId: String? = null,
    val roaster: String? = null,
    val originCountry: String? = null,
    val process: String? = null,
    val dateRange: DateRange = DateRange.ANY,
) {
    /** Anything beyond the search text narrows the list. */
    val isActive: Boolean get() = unratedOnly || buyAgainOnly || sourceId != null || roaster != null ||
        originCountry != null || process != null || dateRange != DateRange.ANY
}

/** Builds the History query; the column list mirrors [CoffeeRow]. */
object HistoryQuery {
    private val SEARCH_COLUMNS = listOf(
        "roaster", "coffee_name", "tasting_notes", "bag_description", "seller_description", "notes",
        "origin_country", "region", "process", "varietal",
    )

    fun dateFloor(range: DateRange, now: Long): Long? = when (range) {
        DateRange.ANY -> null
        DateRange.DAYS_30 -> now - 30L * DAY
        DateRange.DAYS_90 -> now - 90L * DAY
        DateRange.THIS_YEAR -> java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.MONTH, java.util.Calendar.JANUARY); set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    private const val DAY = 24L * 60 * 60 * 1000

    /** The user's text is literal: `%`, `_` and the escape character itself are escaped for LIKE. */
    fun escapeLike(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun build(sort: HistorySort, filter: HistoryFilter, descending: Boolean = sort.desc, now: Long = System.currentTimeMillis()): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val where = mutableListOf<String>()
        filter.sourceId?.let { where += "c.source_id = ?"; args += it }
        filter.roaster?.let { where += "c.roaster = ? COLLATE NOCASE"; args += it }
        filter.originCountry?.let { where += "c.origin_country = ? COLLATE NOCASE"; args += it }
        filter.process?.let { where += "c.process = ? COLLATE NOCASE"; args += it }
        dateFloor(filter.dateRange, now)?.let { where += "c.logged_at >= ?"; args += it }
        if (filter.search.isNotBlank()) {
            val like = "%" + escapeLike(filter.search.trim()) + "%"
            where += SEARCH_COLUMNS.joinToString(" OR ", prefix = "(", postfix = ")") { "c.$it LIKE ? ESCAPE '\\'" }
            repeat(SEARCH_COLUMNS.size) { args += like }
        }
        if (filter.unratedOnly) where += "c.state = 'LOGGED'"
        if (filter.buyAgainOnly) where += "c.buy_again = 1"
        val whereSql = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        // Extracting rows always float to the top; nulls sort last for rating sorts.
        val order = "ORDER BY (c.state = 'EXTRACTING') DESC, c.${sort.column} IS NULL, " +
            "c.${sort.column} ${if (descending) "DESC" else "ASC"}, c.logged_at ${if (descending) "DESC" else "ASC"}"
        val sql = """
            SELECT c.id, c.state, c.logged_at, c.roaster, c.coffee_name, c.tasting_notes, c.overall,
                   c.buy_again, c.tint_light, c.tint_dark, c.accent_light, c.accent_dark, c.palette_fallback,
                   c.extraction_source, c.extraction_attempts, c.extraction_trail, c.next_attempt_at,
                   (SELECT p.thumb FROM coffee_photo p WHERE p.id = c.hero_photo_id) AS hero_thumb,
                   (SELECT COUNT(*) FROM coffee_photo p WHERE p.coffee_id = c.id) AS photo_count
            FROM coffee c $whereSql $order
        """.trimIndent()
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
