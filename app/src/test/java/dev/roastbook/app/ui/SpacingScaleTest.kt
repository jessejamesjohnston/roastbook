package dev.roastbook.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Padding, gaps and offsets come from the family's spacing scale (as in the other Merriment Labs apps).
 * Sizes, radii, widths and breakpoints are separate and not checked here.
 */
class SpacingScaleTest {
    private val scale = setOf(0, 1, 2, 3, 4, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 56, 64, 72, 96).map { it.toDouble() }.toSet()

    @Test fun everyPaddingGapAndOffsetIsOnTheScale() {
        val ui = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val spacing = Regex("""\b(padding|spacedBy|offset|Spacer\(Modifier\.(?:height|width))\(([^()]*)\)""")
        val dp = Regex("""(-?\d+(?:\.\d+)?)\.dp""")
        val off = mutableListOf<String>()
        for (file in ui.walk().filter { it.extension == "kt" }) {
            file.readLines().forEachIndexed { i, line ->
                spacing.findAll(line).forEach { m ->
                    dp.findAll(m.groupValues[2]).forEach { v ->
                        if (kotlin.math.abs(v.groupValues[1].toDouble()) !in scale) off += "${file.name}:${i + 1}: ${m.value}"
                    }
                }
            }
        }
        assertEquals("spacing values off the scale", emptyList<String>(), off)
    }
}
