package dev.roastbook.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app's sentences are English only, so a number or date inside one must be formatted in English
 * too ([dev.roastbook.app.util.English]); Android's own formatting follows the device's language and
 * would put Arabic-Indic digits or a German month into an English line. This scans the sources: a
 * resource that formats a number may only be filled through English, and no other code formats a
 * date, a time or a decimal for display.
 */
class EnglishFormattingTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val sources = root.resolve("java").walkTopDown().filter { it.extension == "kt" }.toList()
    private val helper = "util/English.kt"

    /** Resource names whose text formats a number (`%d`, `%1$d`, `%.1f`). */
    private fun numberResources(): Set<String> {
        val xml = root.resolve("res/values").listFiles()!!.filter { it.extension == "xml" }.joinToString("\n") { it.readText() }
        val numbered = Regex("""%(\d\$)?(\.\d)?[df]""")
        val names = mutableSetOf<String>()
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
            if (numbered.containsMatchIn(it.groupValues[2])) names += it.groupValues[1]
        }
        Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
            if (numbered.containsMatchIn(it.groupValues[2])) names += it.groupValues[1]
        }
        return names
    }

    /** Every match of [rule] across a whole file (a call can span lines), as "File.kt:line". */
    private fun offenders(rule: Regex): List<String> = sources.filterNot { it.path.endsWith(helper) }.flatMap { f ->
        val text = f.readText()
        rule.findAll(text).map { m -> "${f.name}:${text.substring(0, m.range.first).count { it == '\n' } + 1}: ${m.value.trim()}" }.toList()
    }

    @Test fun aSentenceWithANumberIsOnlyFilledInEnglish() {
        val names = numberResources()
        assert(names.size >= 10) { "only ${names.size} number resources found" }
        val direct = Regex(
            """(stringResource|pluralStringResource|getString|getQuantityString)\(\s*R\.(string|plurals)\.(""" +
                names.joinToString("|") + """)\b""",
        )
        assertEquals(emptyList<String>(), offenders(direct))
    }

    @Test fun noDecimalIsFormattedInTheDevicesLanguage() {
        // Only decimal and integer conversions follow the locale's digits; hex ("%02x") never does.
        val localized = Regex(""""[^"]*%(\d\$)?[-#+ 0,(]*\d*(\.\d+)?[df][^"]*"\.format\((?!\s*(java\.util\.)?Locale\.)""")
        assertEquals(emptyList<String>(), offenders(localized))
    }

    /** Dates, times and file sizes (Android's file-size formatter follows the device too). */
    @Test fun noDateOrTimeIsFormattedInTheDevicesLanguage() {
        val rule = Regex("""Formatter\.format\w*FileSize|DateFormat\.get\w*Format|DateUtils\.format|SimpleDateFormat\((?![^)]*Locale\.US)|DateTimeFormatter\.ofPattern\((?![^)]*Locale\.US)""")
        assertEquals(emptyList<String>(), offenders(rule))
    }
}
