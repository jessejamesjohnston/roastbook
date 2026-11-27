package dev.roastbook.app.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.activity.ComponentActivity
import dev.roastbook.app.R
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first download that would need notifications says why in a Material dialog: title, reason,
 * Not now beside Continue at the bottom end. Not now asks nothing and the download starts anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReaderDownloadReasonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun str(id: Int) = compose.activity.getString(id)

    @Test fun theReasonIsADialogAndNotNowStartsTheDownloadWithoutAsking() {
        val started = mutableListOf<String>()
        compose.setContent {
            RoastbookTheme {
                val starter = rememberReaderDownloadStarter { started += it }
                Button(onClick = { starter.start("download") }) { Text("Start") }
                starter.ReasonDialog()
            }
        }
        compose.onNodeWithText("Start").performClick()
        compose.onNode(isDialog()).assertExists()
        fun box(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val title = box(str(R.string.notif_reason_download_title))
        val reason = box(str(R.string.notif_why_download))
        val notNow = box(str(R.string.permission_not_now))
        val go = box(str(R.string.permission_continue))
        assertTrue(reason.top >= title.bottom && notNow.top >= reason.bottom)
        assertTrue("Not now, then Continue at the end", notNow.right <= go.left)
        assertTrue(started.isEmpty())
        compose.onNodeWithText(str(R.string.permission_not_now)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("download"), started)
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
