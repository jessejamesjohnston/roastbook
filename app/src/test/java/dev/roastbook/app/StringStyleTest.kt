package dev.roastbook.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * The wording rules every string in res/values follows (strings.xml, and reactions.xml with the
 * lines shown when a rating lands), checked on the text a person sees (XML entities and Android
 * escapes decoded), across <string>, <plurals> and <string-array>:
 *
 * - A single sentence has no closing period. A block of two or more sentences keeps them.
 * - A trailing ellipsis only marks something in progress ("Backing up…", "Waiting for Wi-Fi…"),
 *   never a label that opens a dialog, a picker or a screen.
 * - Two parts are joined with a middle dot ( · ) or a comma, never an em dash, an en dash, a
 *   hyphen standing in for a dash (" - " or "--") or a semicolon.
 * - American spelling.
 * - Natural contractions ("can't", "it's"), unless the words sit inside quotation marks.
 * - Lists of three or more take a serial comma. That check only prints a warning: it cannot
 *   tell a list from a phrase that happens to contain "and".
 *
 * Entries marked translatable="false" (URLs, the copyright line, the license list's data) are
 * checked too: a person still reads them. Sentence case and plain wording are left to review. An
 * entry may break a rule only through [ALLOWED], and every entry there says why.
 */
class StringStyleTest {

    /** One piece of text: a <string>, or one item of a <plurals> or <string-array>. */
    data class Entry(val name: String, val text: String)

    private enum class Rule { SINGLE_SENTENCE_PERIOD, ELLIPSIS, DASH_OR_SEMICOLON_JOIN, UK_SPELLING, UNCONTRACTED }

    private companion object {
        /** Exceptions, keyed by rule and resource name. Each value is the reason. */
        val ALLOWED: Map<Pair<Rule, String>, String> = mapOf(
            (Rule.SINGLE_SENTENCE_PERIOD to "app_subtitle") to
                "The tagline is quoted word for word, period included, wherever Roastbook is described " +
                "(the Merriment Labs site among them), so the About screen shows it the same way.",
        )

        val UK_SPELLINGS = Regex(
            "\\b(cancell(ed|ing)|colour(s|ed|ful|ing)?|favourites?|behaviours?|" +
                "centre[sd]?|organis(e|ed|es|ing|ation)|recognis(e|ed|es|ing|able))\\b",
            RegexOption.IGNORE_CASE,
        )
        val UNCONTRACTED = Regex(
            "\\b(do not|cannot|will not|you are|it is|does not|is not)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Text inside straight or curly double quotes, which may be someone else's words. */
        val QUOTED = Regex("\"[^\"]*\"|“[^”]*”")
        val SENTENCE_END = Regex("[.!?](?=\\s|$)")
        /** A hyphen used as a dash: one with a space on each side, or two in a row. */
        val HYPHEN_AS_DASH = Regex("\\s-\\s|--")
        val SERIAL_COMMA_MISSING = Regex("\\w[^,.;:·]*, [^,.;:·]+ (and|or) \\w")
    }

    /** Undoes Android's string escapes and its optional surrounding double quotes. */
    private fun decodeAndroid(raw: String): String {
        var s = raw.trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1)
        s = Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(
                    when (val n = s[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> n
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Every entry in a strings file, decoded. XML entities are decoded by the parser. */
    private fun entries(file: File): List<Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableListOf<Entry>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            when (el.tagName) {
                "string" -> out += Entry(name, decodeAndroid(el.textContent))
                "plurals", "string-array" -> {
                    val items = el.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        val key = item.getAttribute("quantity").ifEmpty { j.toString() }
                        out += Entry("$name[$key]", decodeAndroid(item.textContent))
                    }
                }
            }
        }
        return out
    }

    /** The rules [text] breaks, with a short note for each. */
    private fun violations(text: String): Map<Rule, String> {
        val found = linkedMapOf<Rule, String>()
        val t = text.trim()
        val sentences = SENTENCE_END.findAll(t).count()
        if (t.endsWith(".") && !t.endsWith("...") && sentences == 1) {
            found[Rule.SINGLE_SENTENCE_PERIOD] = "a single sentence ends in a period"
        }
        val beforeEllipsis = when {
            t.endsWith("…") -> t.dropLast(1)
            t.endsWith("...") -> t.dropLast(3)
            else -> null
        }
        // A state in progress starts with its "-ing" verb: "Backing up…", "Waiting for Wi-Fi…".
        if (beforeEllipsis != null && !beforeEllipsis.trim().substringBefore(' ').endsWith("ing", ignoreCase = true)) {
            found[Rule.ELLIPSIS] = "a trailing ellipsis on something that is not in progress"
        }
        if ('\u2014' in t || '\u2013' in t || HYPHEN_AS_DASH.containsMatchIn(t) || Regex(";\\s").containsMatchIn(t)) {
            found[Rule.DASH_OR_SEMICOLON_JOIN] = "a dash or semicolon join (use · or a comma)"
        }
        UK_SPELLINGS.find(t)?.let { found[Rule.UK_SPELLING] = "UK spelling \"${it.value}\"" }
        val unquoted = QUOTED.replace(t.replace('’', '\''), "")
        UNCONTRACTED.find(unquoted)?.let { found[Rule.UNCONTRACTED] = "\"${it.value}\" reads better contracted" }
        return found
    }

    /** Every XML file in res/values; the ones without strings (colors, themes) add nothing. */
    private fun valuesFiles(): List<File> {
        val dir = listOf(File("src/main/res/values"), File("app/src/main/res/values")).first { it.isDirectory }
        return dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
    }

    private fun allEntries(): List<Entry> = valuesFiles().flatMap { entries(it) }

    @Test fun everyStringFollowsTheWordingRules() {
        val all = allEntries()
        assertTrue("res/values parsed to ${all.size} entries", all.size > 300)
        assertTrue("reactions.xml is read", all.any { it.name.startsWith("rating_react_5[") })
        assertTrue("plurals items are read", all.any { it.name.startsWith("coffees[") })
        assertTrue("string-array items are read", all.any { it.name.startsWith("sources_seed[") })
        assertTrue("translatable=\"false\" strings are read", all.any { it.name == "settings_copyright" })
        assertTrue("translatable=\"false\" arrays are read", all.any { it.name.startsWith("license_components[") })

        val problems = mutableListOf<String>()
        val used = mutableSetOf<Pair<Rule, String>>()
        for (e in all) {
            for ((rule, note) in violations(e.text)) {
                val key = rule to e.name
                if (key in ALLOWED) used += key else problems += "${e.name}: $note: \"${e.text}\""
            }
            if (SERIAL_COMMA_MISSING.containsMatchIn(e.text)) {
                println("WARNING ${e.name}: a list of three or more may be missing its serial comma: \"${e.text}\"")
            }
        }
        val stale = ALLOWED.keys - used
        if (stale.isNotEmpty()) problems += "allowlist entries no string needs any more: $stale"
        if (problems.isNotEmpty()) fail("Strings that break the wording rules:\n" + problems.joinToString("\n"))
    }

    @Test fun everyAllowlistEntryNamesAStringAndSaysWhy() {
        val names = allEntries().map { it.name }.toSet()
        for ((key, reason) in ALLOWED) {
            assertTrue("$key names a string in res/values", key.second in names)
            assertTrue("$key gives a reason", reason.isNotBlank())
        }
    }

    @Test fun theRulesCatchWhatTheyShould() {
        fun rules(text: String) = violations(text).keys
        assertEquals(setOf(Rule.SINGLE_SENTENCE_PERIOD), rules("Once a day, in the background."))
        assertEquals(setOf(Rule.SINGLE_SENTENCE_PERIOD), rules("Model: Gemma by Google, Apache License 2.0."))
        assertEquals(setOf(Rule.ELLIPSIS), rules("Restore from a backup…"))
        assertEquals(setOf(Rule.ELLIPSIS), rules("Edit name..."))
        assertEquals(setOf(Rule.ELLIPSIS), rules("Save to device, then sharing…"))
        assertEquals(setOf(Rule.DASH_OR_SEMICOLON_JOIN), rules("Working offline \u2014 using saved data"))
        assertEquals(setOf(Rule.DASH_OR_SEMICOLON_JOIN), rules("Paused \u2013 tap to resume"))
        assertEquals(setOf(Rule.DASH_OR_SEMICOLON_JOIN), rules("Drive works; some other apps may not"))
        assertEquals(setOf(Rule.DASH_OR_SEMICOLON_JOIN), rules("Paused - tap to resume"))
        assertEquals(setOf(Rule.DASH_OR_SEMICOLON_JOIN), rules("Paused -- tap to resume"))
        assertEquals(setOf(Rule.DASH_OR_SEMICOLON_JOIN), rules("Paused--tap to resume"))
        assertEquals(setOf(Rule.DASH_OR_SEMICOLON_JOIN), rules("%1\$s - %2\$s"))
        for (uk in listOf("Sign-in cancelled", "Pick a colour", "Favourites", "Odd behaviour", "Centre it", "Organise", "Recognised")) {
            assertEquals(uk, setOf(Rule.UK_SPELLING), rules(uk))
        }
        for (long in listOf("Do not stop", "You cannot miss it", "It will not ring", "You are set", "It is slower", "It does not read", "This is not set")) {
            assertEquals(long, setOf(Rule.UNCONTRACTED), rules(long))
        }
    }

    @Test fun theRulesLeaveGoodTextAlone() {
        for (good in listOf(
            "Backing up…",
            "Checking GitHub…",
            "Waiting for Wi-Fi…",
            "Getting the built-in AI ready…",
            "Looking up...",
            "Your personal coffee journal",
            "Last backup %1\$s · %2\$s · %3\$s",
            "Open a bag and take two photos. We'll do the reading.",
            "Model: Gemma 4 E2B by Google, Apache License 2.0",
            "Copyright © 2026 Jesse Johnston. Published by Merriment Labs.",
            "Rate aroma, flavor, and finish to save",
            "Canceled",
            "Bag colors",
            "Don't stop, it's set, you're done",
            "Google doesn't know the model \"It is new\". Change it in Settings.",
            "The sign says “Do not enter”",
            "Waiting for Wi-Fi",
            "Gemma 4 E2B, one-time download, arm64-v8a or x86-64",
            "Temperature -5 to 40",
            "https://github.com/jessejamesjohnston/roastbook/blob/main/LICENSE",
        )) {
            assertEquals(good, emptyMap<Rule, String>(), violations(good))
        }
    }

    @Test fun decodingUndoesAndroidEscapes() {
        assertEquals("We'll do the reading", decodeAndroid("We\\'ll do the reading"))
        assertEquals("a\nb", decodeAndroid("a\\nb"))
        assertEquals("…", decodeAndroid("\\u2026"))
        assertEquals("  padded  ", decodeAndroid("\"  padded  \""))
        assertFalse(decodeAndroid("\\\"quoted\\\"").contains('\\'))
    }
}
