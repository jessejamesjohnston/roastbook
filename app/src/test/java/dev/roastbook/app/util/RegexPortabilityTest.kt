package dev.roastbook.app.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android compiles regular expressions with ICU, which rejects a bare `}` that the JVM's engine
 * (used by every unit test) accepts as a literal. A pattern like `($|}|,)` passes here and crashes
 * on a device the first time its class loads. This scans every raw-string `Regex("""...""")` in the
 * app's sources for a `}` that neither closes a `{m,n}` quantifier nor sits inside `[...]`.
 */
class RegexPortabilityTest {
    @Test fun noRegexHasABareClosingBrace() {
        val root = File(System.getProperty("user.dir")!!, "src/main/java")
        val literal = Regex("Regex\\(\"\"\"(.*?)\"\"\"")
        val bad = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            literal.findAll(f.readText()).map { it.groupValues[1] }
                // A pattern built from a string template is checked only up to its first template.
                .map { it.substringBefore("\${") }.filter(::hasBareBrace).map { "${f.name}: $it" }
        }.toList()
        assertTrue("Escape these as \\}: $bad", bad.isEmpty())
    }

    @Test fun theCheckItselfCatchesTheCase() {
        assertTrue(hasBareBrace("""^\s*($|}|,)"""))
        assertTrue(!hasBareBrace("""^\s*($|\}|,)"""))
        assertTrue(!hasBareBrace("""[,}"]"""))
        assertTrue(!hasBareBrace("""\d{2,4}"""))
    }

    private fun hasBareBrace(p: String): Boolean {
        var i = 0; var inClass = false; var inQuant = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> inQuant = true
                c == '}' -> if (inQuant) inQuant = false else return true
            }
            i++
        }
        return false
    }
}
