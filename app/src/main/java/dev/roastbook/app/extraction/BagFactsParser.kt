package dev.roastbook.app.extraction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tolerant parser for the model's answer. Accepts a JSON object (possibly wrapped in prose or
 * code fences) and, failing that, the YAML-shaped "key: value" drift seen on multi-image runs.
 * Returns null only when nothing key-shaped could be found.
 */
object BagFactsParser {
    private val keys = listOf(
        "roaster", "coffee_name", "origin_country", "region", "producer", "process", "varietal",
        "elevation_m", "roast_level", "roast_date", "bag_size_g", "tasting_notes", "bag_description",
        "seller_description",
    )

    fun parse(raw: String?): BagFacts? {
        if (raw.isNullOrBlank()) return null
        val text = raw.replace("```json", "").replace("```", "").trim()
        parseJson(text)?.let { return it }
        return parseLoose(text)
    }

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun parseJson(text: String): BagFacts? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = text.substring(start, end + 1)
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: runCatching { json.parseToJsonElement(repair(body)) as? JsonObject }.getOrNull()
            ?: return null
        val map = mutableMapOf<String, Any?>()
        for (k in keys) {
            val v = obj[k] ?: continue
            if (v is JsonNull) continue
            map[k] = when (v) {
                is JsonPrimitive -> v.content
                is JsonArray -> v.mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content }
                else -> null
            }
        }
        return fromMap(map)
    }

    /**
     * Lenient repair for the comma slips measured on LiteRT-LM output (doubled commas, a comma on its
     * own line, a missing comma between entries, a trailing comma). Only used when the text does not
     * parse as it is.
     */
    internal fun repair(j: String): String = j
        .replace(Regex("""(?m)^\s*,\s*$"""), "")
        .replace(Regex("""(?m)^(\s*),\s*""""), "$1\"")
        .replace(Regex(""",(\s*,)+"""), ",")
        .replace(Regex(""",(\s*[}\]])"""), "$1")
        .replace(Regex("""("[^"]*"|]|\d|null|true|false)(\s*\n\s*)(")"""), "$1,$2$3")

    /** "key: value" lines; lists as [a, b] or as following "- item" lines. */
    private fun parseLoose(text: String): BagFacts? {
        val map = mutableMapOf<String, Any?>()
        var listKey: String? = null
        var list: MutableList<String>? = null
        for (line in text.lines()) {
            val t = line.trim()
            val m = Regex("^\"?([a-z_]+)\"?\\s*:\\s*(.*)$").find(t)
            if (m == null) {
                // Inside a multi-line list: "- item", "item", or the closing bracket.
                if (listKey != null && list != null) {
                    if (t.startsWith("]")) { listKey = null; list = null; continue }
                    val item = t.removePrefix("- ").trimEnd(',').trim().trim('"', '\'')
                    if (item.isNotEmpty() && item != "null") list.add(item)
                }
                continue
            }
            val key = m.groupValues[1]; val value = m.groupValues[2].trim().trimEnd(',')
            if (key !in keys) continue
            listKey = null; list = null
            when {
                value.startsWith("[") -> {
                    val inner = value.removePrefix("[").substringBefore("]")
                    val items = inner.split(",").map { it.trim().trim('"', '\'') }.filter { it.isNotBlank() && it != "null" }
                    map[key] = items
                    if (!value.contains("]")) { listKey = key; list = items.toMutableList(); map[key] = list }
                }
                value.isEmpty() -> { listKey = key; list = mutableListOf(); map[key] = list }
                else -> map[key] = value.trim('"', '\'')
            }
        }
        if (map.isEmpty()) return null
        return fromMap(map)
    }

    private fun fromMap(m: Map<String, Any?>): BagFacts = BagFacts(
        roaster = str(m["roaster"]),
        coffeeName = str(m["coffee_name"]),
        originCountry = str(m["origin_country"]),
        region = str(m["region"]),
        producer = str(m["producer"]),
        process = str(m["process"]),
        varietal = list(m["varietal"]),
        elevationM = int(m["elevation_m"], max = 5000),
        roastLevel = str(m["roast_level"]),
        roastDate = str(m["roast_date"]),
        bagSizeG = int(m["bag_size_g"], max = 20000),
        tastingNotes = list(m["tasting_notes"]),
        bagDescription = str(m["bag_description"]),
        sellerDescription = str(m["seller_description"]),
    )

    private fun str(v: Any?): String? {
        val s = when (v) { null -> return null; is String -> v; else -> v.toString() }.trim()
        return if (s.isEmpty() || s.equals("null", true) || s.equals("unknown", true) || s == "—" || s == "-") null else s
    }

    private fun int(v: Any?, max: Int): Int? {
        val n = when (v) {
            null -> return null
            is Number -> v.toDouble()
            is String -> Regex("-?\\d+(\\.\\d+)?").find(v)?.value?.toDoubleOrNull() ?: return null
            else -> return null
        }
        val i = Math.round(n).toInt()
        return if (i <= 0 || i > max) null else i
    }

    private fun list(v: Any?): List<String> = when (v) {
        null -> emptyList()
        is List<*> -> v.mapNotNull { str(it) }
        is String -> if (v.contains("|")) v.split("|").mapNotNull { str(it) } else listOfNotNull(str(v))
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
