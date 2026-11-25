package dev.roastbook.app.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The language list lives in three places; a language missing from one is a silent bug. */
class LanguagesTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    @Test fun localesConfigMatchesCode() {
        val xml = File(res, "xml/locales_config.xml").readText()
        val declared = Regex("""android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
        assertEquals(AppLanguage.supported.toSet(), declared)
    }

    @Test fun everyTranslationIsOffered() {
        val translated = res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").exists() }
            .map { it.name.removePrefix("values-").replace("-r", "-") }
            .toSet()
        // English is the unqualified default folder.
        assertEquals(AppLanguage.supported.toSet() - "en", translated)
    }
}
