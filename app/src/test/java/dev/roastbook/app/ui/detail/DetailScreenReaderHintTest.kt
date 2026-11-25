package dev.roastbook.app.ui.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import dev.roastbook.app.R
import dev.roastbook.app.data.db.ExtractionSource
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.performClick
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a phone that could run the on-device reader, the note points to downloading it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h2600dp")
class DetailScreenReaderHintTest : DetailScreenTestBase(eligibleForReader = true) {

    @Test fun aPhoneWithoutItsOwnAiIsPointedToTheReader() {
        insert { copy(roaster = "Northwind", extractionSource = ExtractionSource.OCR, extractionTrail = "nano=unsupported;local=absent;cloud=off;guess=2") }
        show()
        compose.awaitUntil { vm.readerOffered.value }
        compose.onNodeWithText(str(R.string.what_hint_download)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.what_hint_key)).assertDoesNotExist()
    }

    @Test fun aPhoneWhoseOwnAiKeepsFailingIsPointedToTheReaderToo() {
        runBlocking { f.readerPrefs.recordNano(ok = false, reason = "timeout") }
        insert { copy(roaster = "Northwind", extractionSource = ExtractionSource.OCR, extractionTrail = "nano=failed;local=absent;cloud=off;guess=2") }
        show()
        compose.awaitUntil { vm.nanoFailing.value && vm.readerOffered.value }
        compose.onNodeWithText(str(R.string.what_hint_download)).assertIsDisplayed()
    }

    @Test fun aPoorBuiltInReadingOffersTheReaderInPlace() {
        insert { copy(roaster = null, producer = "Hollin Vale", extractionSource = ExtractionSource.NANO, extractionTrail = "nano=read;nanoq=poor") }
        show()
        compose.awaitUntil { vm.readerState.value != null && vm.readerSettings.value != null }
        compose.onNodeWithText(str(R.string.detail_reader_offer_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_reader_offer_not_now)).performClick()
        compose.awaitUntil { vm.readerSettings.value?.offerDeclines == 1 }
        compose.onNodeWithText(str(R.string.detail_reader_offer_title)).assertDoesNotExist()
        assertEquals(setOf("c1"), vm.readerSettings.value!!.offerDeclinedIds)
    }

    @Test fun yesToTheOfferChoosesTheReaderAndFollowsTheDownload() {
        insert { copy(roaster = null, producer = "Hollin Vale", extractionSource = ExtractionSource.NANO, extractionTrail = "nano=read;nanoq=poor") }
        show()
        compose.awaitUntil { vm.readerState.value != null && vm.readerSettings.value != null }
        compose.onNodeWithText(str(R.string.detail_reader_offer_title)).assertIsDisplayed()
        compose.onNodeWithText("Download (", substring = true).performClick()
        compose.awaitUntil { vm.readerSettings.value?.preferReader == true }
        assertEquals(setOf("c1"), vm.readerSettings.value!!.pendingRereads)
        compose.awaitUntil { runCatching { compose.onNodeWithText(str(R.string.detail_reader_waiting)).assertExists() }.isSuccess }
        compose.onNodeWithText(str(R.string.detail_reader_offer_title)).assertDoesNotExist()
    }
}
