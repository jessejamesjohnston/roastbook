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

    fun parse(raw: String?): BagFacts? = parseChecked(raw)?.facts

    /** The facts, and whether the answer was a JSON object (as asked) or needed the loose path. */
    data class Parsed(val facts: BagFacts, val wellFormed: Boolean)

    fun parseChecked(raw: String?): Parsed? {
        if (raw.isNullOrBlank()) return null
        val text = raw.replace("```json", "").replace("```", "").trim()
        parseJson(text)?.let { return Parsed(it, wellFormed = true) }
        return parseLoose(text)?.let { Parsed(it, wellFormed = false) }
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

    /**
     * "key: value" lines, and the pairs of a JSON object that failed to parse as a whole (one bad
     * value breaks every other key otherwise). Each value is read as one token: a quoted string up to
     * its closing quote, a bare null/true/false, a [list], or (YAML style) the unquoted rest of the
     * line. A quote closes a string only where the value can end (end of line, a brace, or a comma
     * before the end or the next key), so unescaped quotes inside the text are kept. Text after a
     * value that is not another key is ignored, so a stray string after a bare null
     * (`"producer": null "Name"`) is not glued onto it. A quoted string that never closes (an
     * answer cut off at its token limit) is dropped rather than kept half-written. Several pairs may
     * share a line, and the first may share it with the opening brace. Lists also span lines as
     * [a, b] or as following "- item" lines.
     */
    private fun parseLoose(text: String): BagFacts? {
        val map = mutableMapOf<String, Any?>()
        var list: MutableList<String>? = null
        for (line in text.lines()) {
            val t = line.trim()
            var pos: Int
            var m = keyAt.matchAt(t, 0)
            if (m == null) {
                // Inside a multi-line list: "- item", "item", or the closing bracket.
                if (list != null) {
                    if (t.startsWith("]")) { list = null; continue }
                    val item = t.removePrefix("- ").trimEnd(',').trim().trim('"', '\'')
                    if (item.isNotEmpty() && item != "null") list.add(item)
                }
                continue
            }
            list = null
            while (m != null) {
                val key = m.groupValues[1]
                pos = m.range.last + 1
                val v = readValue(t, pos)
                pos = v.end
                if (key in keys) {
                    when {
                        v.openList != null -> { list = v.openList; map[key] = v.openList }
                        v.dropped -> map.remove(key)
                        else -> map[key] = v.value
                    }
                }
                // The next pair on this line, if any: right here, or after a stray token.
                m = keyAt.matchAt(t, pos) ?: nextPair.find(t, pos)?.let { keyAt.matchAt(t, it.range.first) }
            }
        }
        if (map.isEmpty()) return null
        return fromMap(map)
    }

    /** A key at the start of the text: optional brace, commas, whitespace and quotes around it. */
    private val keyAt = Regex("""[\s{,]*"?([a-z_]+)"?\s*:\s*""")
    /** A later quoted key on the same line, after something that was not one. */
    private val nextPair = Regex(""",\s*"[a-z_]+"\s*:""")
    /** A bare literal, only where it ends the value: "True North Roasters" is a name, not `true`. */
    private val literal = Regex("""^(null|true|false)(?=\s*($|[,}"]))""", RegexOption.IGNORE_CASE)
    /** What may follow a string's closing quote: the line's end, a brace, or a comma before the end or the next key. */
    private val afterString = Regex("""^\s*($|}|,\s*($|"?[a-z_]+"?\s*:))""")

    /** One value read from [t] at [from]; [openList] is set when a list continues on later lines. */
    private class Value(val value: Any?, val end: Int, val openList: MutableList<String>? = null, val dropped: Boolean = false)

    private fun readValue(t: String, from: Int): Value {
        val rest = t.substring(from)
        return when {
            rest.isEmpty() -> Value(null, t.length, openList = mutableListOf())
            rest.startsWith("\"") -> {
                val sb = StringBuilder()
                var i = from + 1
                while (i < t.length) {
                    val c = t[i]
                    if (c == '\\' && i + 1 < t.length) {
                        sb.append(when (val e = t[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> e }); i += 2; continue
                    }
                    // A quote closes the string only where the value can end; an unescaped quote
                    // inside the text (`"The "house" lot"`) stays part of it.
                    if (c == '"' && afterString.containsMatchIn(t.substring(i + 1))) return Value(sb.toString(), i + 1)
                    sb.append(c); i++
                }
                Value(null, t.length, dropped = true)
            }
            rest.startsWith("[") -> {
                val close = rest.indexOf(']')
                val inner = rest.substring(1, if (close < 0) rest.length else close)
                val items = inner.split(",").map { it.trim().trim('"', '\'') }.filter { it.isNotBlank() && it != "null" }
                if (close < 0) Value(null, t.length, openList = items.toMutableList())
                else Value(items, from + close + 1)
            }
            literal.containsMatchIn(rest) -> Value(null, from + literal.find(rest)!!.range.last + 1)
            else -> {
                // Unquoted (YAML style): the rest of the line, up to the next quoted key if one follows.
                val stop = nextPair.find(t, from)?.range?.first ?: t.length
                Value(t.substring(from, stop).trim().trimEnd(',', '}').trim().trim('"', '\''), stop)
            }
        }
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
