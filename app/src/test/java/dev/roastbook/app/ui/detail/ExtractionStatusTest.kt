package dev.roastbook.app.ui.detail

import android.text.format.DateFormat
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.ui.theme.RoastbookTheme
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "what happened" note, the reading lines, and the "Read by" attribution under the facts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExtractionStatusTest {
    @get:Rule val compose = createComposeRule()
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun coffee(
        source: ExtractionSource,
        trail: String? = null,
        state: CoffeeState = CoffeeState.LOGGED,
        ocr: String? = null,
    ) = CoffeeEntity("c1", 1, 1, 1, state, extractionSource = source, extractionTrail = trail, ocrText = ocr)

    private var rereads = 0
    private var dismissals = 0

    private fun card(c: CoffeeEntity, readerOffered: Boolean = false, nanoFailing: Boolean = false, hasKey: Boolean = false) {
        compose.setContent {
            RoastbookTheme {
                ExtractionStatusCard(c, readerOffered = readerOffered, nanoFailing = nanoFailing, hasKey = hasKey,
                    onReread = { rereads++ }, onDismiss = { dismissals++ })
            }
        }
    }

    private fun exists(text: String) = runCatching { compose.onNodeWithText(text).assertExists() }.isSuccess

    @Test fun aPoorReadSaysSoWithWhyWhetherItGuessedAndWhatWouldHelp() {
        card(coffee(ExtractionSource.OCR, trail = "nano=failed;local=absent;cloud=off;guess=3", ocr = "label text"))
        compose.onNodeWithText(str(R.string.detail_poor_read)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.what_ocr_nano_failed)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.what_guess_filled)).assertIsDisplayed()
        // No key and no reader offered here: the hint points at a cloud key.
        compose.onNodeWithText(str(R.string.what_hint_key)).assertIsDisplayed()
    }

    @Test fun aRefusedKeySaysToCheckItInSettings() {
        card(coffee(ExtractionSource.OCR, trail = "nano=unsupported;local=unsupported;cloud=failed;cloudwhy=key;guess=2", ocr = "label text"), hasKey = true)
        compose.onNodeWithText(str(R.string.what_ocr_cloud_key)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.what_ocr_cloud_failed)).assertDoesNotExist()
    }

    @Test fun anUnknownModelIsNamedSoATypoShows() {
        card(coffee(ExtractionSource.OCR, trail = "nano=unsupported;local=unsupported;cloud=failed;cloudwhy=model;cloudmodel=gemini-3.8-flsh;guess=2", ocr = "label text"), hasKey = true)
        compose.onNodeWithText(str(R.string.what_ocr_cloud_model_named, "gemini-3.8-flsh")).assertIsDisplayed()
    }

    @Test fun readAgainAndCloseReportBackToTheScreen() {
        card(coffee(ExtractionSource.NONE, trail = "nano=empty"))
        compose.onNodeWithText(str(R.string.what_reread)).performClick()
        assertEquals(1, rereads)
        compose.onNodeWithContentDescription(str(R.string.detail_dismiss_note)).performClick()
        assertEquals(1, dismissals)
    }

    @Test fun aReaderThatCouldRunHerePointsToTheDownloadInsteadOfAKey() {
        card(coffee(ExtractionSource.OCR, trail = "nano=unsupported;local=absent;cloud=off;guess=0"), readerOffered = true)
        compose.onNodeWithText(str(R.string.what_hint_download)).assertIsDisplayed()
        assertFalse(exists(str(R.string.what_hint_key)))
        // Unsupported is not a poor read: reading it again won't help, so no headline.
        assertFalse(exists(str(R.string.detail_poor_read)))
        compose.onNodeWithText(str(R.string.what_guess_none)).assertIsDisplayed()
    }

    @Test fun aLocalReadIsExplainedWithoutOfferingAReread() {
        card(coffee(ExtractionSource.LOCAL, trail = "nano=unsupported;local=read"), hasKey = true)
        compose.onNodeWithText(str(R.string.what_local_read)).assertIsDisplayed()
        assertFalse(exists(str(R.string.what_reread)))
    }

    @Test fun nothingShowsWhileTheBagIsStillBeingRead() {
        card(coffee(ExtractionSource.NONE, trail = "nano=failed", state = CoffeeState.EXTRACTING))
        compose.waitForIdle()
        assertFalse(exists(str(R.string.what_nothing_readable)))
        assertFalse(exists(str(R.string.detail_poor_read)))
    }

    @Test fun nothingShowsWhenThePhonesOwnAiReadIt() {
        card(coffee(ExtractionSource.NANO, trail = "nano=read"))
        compose.waitForIdle()
        assertFalse(exists(str(R.string.what_reread)))
        assertFalse(exists(str(R.string.detail_poor_read)))
    }

    @Test fun preparingTheReaderIsTheOnlyTrailThatCountsAsPreparing() {
        assertTrue(isPreparingReader(CoffeeState.EXTRACTING, "nano=preparing"))
        assertFalse(isPreparingReader(CoffeeState.LOGGED, "nano=preparing"))
        assertFalse(isPreparingReader(CoffeeState.EXTRACTING, "nano=failed"))
        assertFalse(isPreparingReader(CoffeeState.EXTRACTING, null))
    }

    /** The reading lines for each (trail, retry time) case, from one composition. */
    private fun lines(vararg cases: Pair<String?, Long?>): List<Pair<String, String>> {
        var out: List<Pair<String, String>> = emptyList()
        compose.setContent { out = cases.map { (trail, next) -> extractingLines(trail, next) } }
        compose.waitForIdle()
        return out
    }

    @Test fun anOrdinaryReadSaysItIsReadingTheBag() {
        assertEquals(listOf(str(R.string.row_extracting) to str(R.string.row_extracting_subtitle)), lines(null to null))
    }

    @Test fun aParkedFirstDownloadSaysWhenItWillTryAgain() {
        val at = System.currentTimeMillis() + 60 * 60 * 1000
        val time = DateFormat.getTimeFormat(context).format(Date(at))
        assertEquals(listOf(str(R.string.row_preparing) to str(R.string.row_preparing_retry_at, time)), lines("nano=preparing" to at))
    }

    @Test fun aFirstDownloadWithNoRetryTimeAheadSaysItIsDownloading() {
        val expected = str(R.string.row_preparing) to str(R.string.row_preparing_subtitle)
        assertEquals(listOf(expected, expected), lines("nano=preparing" to System.currentTimeMillis() - 1_000, "nano=preparing" to null))
    }

    /**
     * A one-line note is as tall as its line plus the card's padding: the close button keeps its
     * 48dp touch target without stretching the card below the text.
     */
    @Test @Config(qualifiers = "w600dp-h800dp")
    fun aOneLineNoteIsNoTallerThanItsLine() {
        compose.setContent {
            RoastbookTheme {
                ExtractionStatusCard(coffee(ExtractionSource.LOCAL, trail = "nano=unsupported;local=read"), readerOffered = false,
                    nanoFailing = false, hasKey = true, onReread = {}, onDismiss = {}, modifier = Modifier.testTag("note"))
            }
        }
        val dp = context.resources.displayMetrics.density
        val card = compose.onNodeWithTag("note").fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithText(str(R.string.what_local_read)).fetchSemanticsNode().boundsInRoot
        // The card sits 6dp inside its slot and pads its content by 12dp, top and bottom.
        val spaceBelowLine = (card.bottom - 18 * dp) - line.bottom
        assertTrue("${spaceBelowLine / dp}dp of empty card under the line", spaceBelowLine <= 1 * dp)
        val close = compose.onNodeWithContentDescription(str(R.string.detail_dismiss_note)).fetchSemanticsNode().boundsInRoot
        assertTrue("close target ${close.width / dp}x${close.height / dp}dp", close.width >= 47.5f * dp && close.height >= 47.5f * dp)
        compose.onNodeWithContentDescription(str(R.string.detail_dismiss_note)).performClick()
    }

    /** The attribution line for each coffee, from one composition. */
    private fun labels(vararg cs: CoffeeEntity): List<String?> {
        var out: List<String?> = emptyList()
        compose.setContent { out = cs.map { extractionSourceLabel(it) } }
        compose.waitForIdle()
        return out
    }

    @Test fun eachReaderIsNamedUnderTheFacts() {
        assertEquals(
            listOf(R.string.source_line_nano, R.string.source_line_local, R.string.source_line_cloud, R.string.source_line_ocr).map { str(it) },
            labels(coffee(ExtractionSource.NANO), coffee(ExtractionSource.LOCAL), coffee(ExtractionSource.CLOUD), coffee(ExtractionSource.OCR, trail = "guess=0")),
        )
    }

    @Test fun guessedFieldsAreNamedAsAGuessAndNothingReadIsNotAttributed() {
        val out = labels(coffee(ExtractionSource.OCR, trail = "nano=unsupported;guess=2"), coffee(ExtractionSource.NONE))
        assertEquals(str(R.string.source_guess), out[0])
        assertNull(out[1])
    }
}
