package dev.roastbook.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.roastbook.app.ui.theme.RoastbookTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The keyboard focus ring (as in the other Merriment Labs apps): a 3dp line in the accent color on the
 * control's own bounds, drawn only while it has focus, kept apart from a filled control's own fill
 * by a thin band of the page color; and every control in the app's sources asks for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusRingTest {
    @get:Rule val compose = createComposeRule()


    private fun near(a: Color, b: Color) = abs(a.red - b.red) < 0.06f && abs(a.green - b.green) < 0.06f && abs(a.blue - b.blue) < 0.06f

    @Test fun aFilledButtonShowsTheRingAndAGapOnlyWhileFocused() {
        val focus = FocusRequester()
        var accent = Color.Unspecified
        var page = Color.Unspecified
        compose.setContent {
            RoastbookTheme(darkTheme = false) {
                accent = MaterialTheme.colorScheme.primary
                page = MaterialTheme.colorScheme.surface
                Box(Modifier.background(page).padding(8.dp)) {
                    // A filled pill, as a filled button draws, focusable even in touch mode (a Material
                    // button takes focus only from a keyboard, which Robolectric's window doesn't model).
                    Box(
                        Modifier.testTag("b").size(80.dp, 40.dp).focusRing(CircleShape, onFill = true).focusRequester(focus).focusable()
                            .background(accent, CircleShape),
                    )
                }
            }
        }
        val before = compose.onNodeWithTag("b").captureToImage().toPixelMap()
        val midY = before.height / 2
        assertTrue("unfocused: the side is the fill, no ring", near(before[1, midY], accent) && near(before[(4 * density()).toInt(), midY], accent))
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        compose.waitForIdle()
        compose.onNodeWithTag("b").assertIsFocused()
        val after = compose.onNodeWithTag("b").captureToImage().toPixelMap()
        val d = density()
        assertTrue("the ring runs along the top edge", near(after[after.width / 2, (1 * d).toInt()], accent))
        assertTrue("a page-colored gap sits between the ring and the fill", near(after[(4 * d).toInt(), midY], page))
        assertTrue("the fill is still the fill inside the gap", near(after[(8 * d).toInt(), midY], accent))
    }

    private fun density() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    /**
     * Every control in the app's sources is marked: a Material control's own arguments carry
     * focusRing, and a clickable, selectable or toggleable modifier chain carries it before the
     * click. A Switch, RadioButton or Checkbox whose row takes the click is not focusable itself.
     * ChoiceChip draws the ring inside.
     */
    @Test fun everyControlInTheAppDrawsTheRing() {
        val ui = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val buttons = Regex(
            """\b(IconButton|TextButton|FilledTonalButton|FilledTonalIconButton|FilledIconButton|OutlinedButton|OutlinedIconButton|Button|""" +
                """DropdownMenuItem|FilterChip|AssistChip|InputChip|SuggestionChip|FloatingActionButton|ExtendedFloatingActionButton|""" +
                """SmallFloatingActionButton|SegmentedButton|Switch|Checkbox|RadioButton|Card|Surface)\(""",
        )
        val chained = Regex("""\.(clickable|selectable|toggleable|combinedClickable)\(""")
        val missing = mutableListOf<String>()
        for (file in ui.walk().filter { it.extension == "kt" }) {
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                val code = line.trimStart()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("import ") || Regex("""\bfun [A-Z]""").containsMatchIn(line)) return@forEachIndexed
                buttons.findAll(line).forEach { m ->
                    val own = callArguments(lines, i, m.range.last)
                    val inert = (m.groupValues[1] in setOf("Switch", "Checkbox", "RadioButton") && Regex("""on(CheckedChange|Click) = null""").containsMatchIn(own)) ||
                        (m.groupValues[1] in setOf("Card", "Surface") && "onClick" !in own)
                    if (!inert && "focusRing" !in own) missing += "${file.name}:${i + 1}: $code"
                }
                chained.findAll(line).forEach { m ->
                    val before = modifierChain(lines, i) + line.substring(0, m.range.first)
                    if ("focusRing" !in before && "FOCUS_RING_WIDTH" !in before) missing += "${file.name}:${i + 1}: $code"
                }
            }
        }
        assertEquals("controls without the focus ring", emptyList<String>(), missing)
    }

    /** The text of a call's arguments, from its opening parenthesis to the matching close. */
    private fun callArguments(lines: List<String>, row: Int, open: Int): String {
        val text = StringBuilder()
        var depth = 0
        for (r in row until lines.size) {
            val line = lines[r]
            for (c in (if (r == row) open else 0) until line.length) {
                when (line[c]) { '(' -> depth++; ')' -> depth-- }
                text.append(line[c])
                if (depth == 0) return text.toString()
            }
            text.append('\n')
        }
        return text.toString()
    }

    /** The lines of the modifier chain above [row], back to the line that starts it with `Modifier` or `modifier`. */
    private fun modifierChain(lines: List<String>, row: Int): String {
        val start = Regex("""(^|[^.\w])[Mm]odifier\b""")
        var r = row
        if (start.containsMatchIn(lines[row])) return ""
        while (r > 0 && row - r < 20) {
            r--
            if (start.containsMatchIn(lines[r])) break
        }
        return lines.subList(r, row).joinToString("\n") + "\n"
    }
}
