package dev.roastbook.app.ui.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import dev.roastbook.app.R
import dev.roastbook.app.data.db.ExtractionSource
import kotlinx.coroutines.runBlocking
import org.junit.Test
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
}
