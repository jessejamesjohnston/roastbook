package dev.roastbook.app.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A destructive question gives Cancel the first focus, so Enter by habit keeps the thing
 * (app-standard section 12, rules 23 and 34).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class InitialFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theMarkedElementTakesFocusWhenItAppears() {
        compose.setContent { Box(Modifier.testTag("cancel").size(48.dp).initialFocus().focusable()) }
        compose.waitForIdle()
        compose.onNodeWithTag("cancel").assertIsFocused()
    }

    /** Every dialog whose confirm deletes, discards or replaces gives its dismiss button the first focus. */
    @Test fun everyDestructiveQuestionFocusesItsCancel() {
        val ui = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val destructive = listOf("R.string.detail_delete_yes", "R.string.reader_delete_yes", "R.string.settings_restore_yes", "R.string.edit_discard)")
        val found = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (file in ui.walk().filter { it.extension == "kt" }) {
            val text = file.readText()
            var at = text.indexOf("AlertDialog(")
            while (at >= 0) {
                val block = call(text, at + "AlertDialog".length)
                val confirm = block.substringAfter("confirmButton", "").substringBefore("dismissButton")
                if (destructive.any { it in confirm }) {
                    found += file.name
                    if ("initialFocus()" !in block.substringAfter("dismissButton", "")) missing += file.name
                }
                at = text.indexOf("AlertDialog(", at + 1)
            }
        }
        assertEquals("four destructive questions", 4, found.size)
        assertEquals(emptyList<String>(), missing)
    }

    private fun call(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) return text.substring(open, i + 1) } }
        }
        return text.substring(open)
    }
}
