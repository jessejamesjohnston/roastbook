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
        // Not now on this bag's offer: the note is what points to the reader then.
        runBlocking { f.readerPrefs.recordNano(ok = false, reason = "timeout"); f.readerPrefs.declineOffer("c1") }
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

    private fun grantNotifications() = org.robolectric.Shadows.shadowOf(f.context as android.app.Application)
        .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

    private fun poorBag(id: String = "c1") =
        insert(id) { copy(roaster = null, producer = "Hollin Vale", extractionSource = ExtractionSource.NANO, extractionTrail = "nano=read;nanoq=poor") }

    private fun awaitShown(text: String) =
        compose.awaitUntil { runCatching { compose.onNodeWithText(text).assertExists() }.isSuccess }

    private fun awaitGone(text: String) =
        compose.awaitUntil { runCatching { compose.onNodeWithText(text).assertDoesNotExist() }.isSuccess }

    @Test fun aDoubleTapOnNotNowCountsOnce() {
        poorBag()
        show()
        compose.awaitUntil { vm.readerState.value != null && vm.readerSettings.value != null }
        val notNow = compose.onNodeWithText(str(R.string.detail_reader_offer_not_now))
        notNow.performClick()
        vm.declineReaderOffer() // the second tap, landing before the card goes
        awaitWritesDone()
        assertEquals(1, vm.readerSettings.value!!.offerDeclines)
    }

    @Test fun theOfferCardAndTheNoteNeverBothPointAtTheReader() {
        runBlocking { f.readerPrefs.recordNano(ok = false, reason = "timeout") }
        insert { copy(roaster = "Northwind", extractionSource = ExtractionSource.OCR, extractionTrail = "nano=failed;local=absent;cloud=off;guess=2") }
        show()
        compose.awaitUntil { vm.nanoFailing.value && vm.readerOffered.value && vm.readerSettings.value != null }
        awaitShown(str(R.string.detail_reader_offer_title))
        compose.onNodeWithText(str(R.string.what_hint_download)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.what_hint_key)).assertDoesNotExist()
    }

    @Test fun theFirstDownloadFromTheOfferExplainsNotificationsBeforeAndroidAsks() {
        poorBag()
        show()
        compose.awaitUntil { vm.readerState.value != null && vm.readerSettings.value != null }
        compose.onNodeWithText("Download (", substring = true).performClick()
        awaitShown(str(R.string.notif_why_download))
        // Nothing is chosen or downloading until the question is settled.
        assertEquals(false, vm.readerSettings.value!!.preferReader)
        compose.onNodeWithText(str(R.string.permission_continue)).assertIsDisplayed()
    }

    /** Not now or Download on the offer: neither may show again once Download is tapped. */
    private fun offerButtonsShown() =
        compose.onNodeWithText(str(R.string.detail_reader_offer_not_now)).exists() ||
            compose.onNodeWithText("Download (", substring = true).exists()

    /**
     * The card that follows the download: starting, or (once the test's job is queued, since its
     * network rule is never met here) waiting for Wi-Fi.
     */
    private fun startingOrQueued() = compose.onNodeWithText(str(R.string.detail_reader_waiting)).exists() && (
        compose.onNodeWithText(str(R.string.detail_reader_starting)).exists() ||
            compose.onNodeWithText(str(R.string.reader_waiting_wifi)).exists()
        )

    @Test fun downloadGoesStraightToTheStartingCard() {
        grantNotifications()
        poorBag()
        show()
        compose.awaitUntil { vm.readerState.value != null && vm.readerSettings.value != null }
        compose.onNodeWithText("Download (", substring = true).performClick()
        // The first frame after the tap, before the choice is written: already the starting card.
        assertEquals(false, offerButtonsShown())
        assertEquals(true, startingOrQueued())
        compose.awaitUntil { vm.readerSettings.value?.pendingRereads == setOf("c1") }
        assertEquals(false, offerButtonsShown())
    }

    @Test fun allowOnTheNotificationQuestionGoesStraightToTheStartingCard() = answerTheNotificationQuestion(allow = true)

    @Test fun dontAllowOnTheNotificationQuestionGoesStraightToTheStartingCardToo() = answerTheNotificationQuestion(allow = false)

    private fun answerTheNotificationQuestion(allow: Boolean) {
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?,
            ) {
                if (allow) grantNotifications()
                dispatchResult(requestCode, allow)
            }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        poorBag()
        show(wrap = { content ->
            androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) { content() }
        })
        compose.awaitUntil { vm.readerState.value != null && vm.readerSettings.value != null }
        compose.onNodeWithText("Download (", substring = true).performClick()
        awaitShown(str(R.string.notif_why_download))
        compose.onNodeWithText(str(R.string.permission_continue)).performClick()
        // Android's answer has come back; the choice is still being written.
        assertEquals(false, offerButtonsShown())
        assertEquals(true, startingOrQueued())
        compose.awaitUntil { vm.readerSettings.value?.pendingRereads == setOf("c1") }
        assertEquals(false, offerButtonsShown())
    }

    @Test fun cancelOnTheCardStopsTheDownloadClearsTheCardAndTheChoice() {
        grantNotifications()
        poorBag()
        show()
        compose.awaitUntil { vm.readerState.value != null && vm.readerSettings.value != null }
        compose.onNodeWithText("Download (", substring = true).performClick()
        compose.awaitUntil { vm.readerSettings.value?.preferReader == true }
        awaitShown(str(R.string.reader_cancel))
        compose.onNodeWithText(str(R.string.reader_cancel)).performClick()
        compose.awaitUntil { vm.readerSettings.value?.pendingRereads?.isEmpty() == true }
        assertEquals(false, vm.readerSettings.value!!.preferReader)
        awaitGone(str(R.string.detail_reader_waiting))
        // The bag counts as answered: the offer doesn't come straight back.
        compose.onNodeWithText(str(R.string.detail_reader_offer_title)).assertDoesNotExist()
    }

    @Test fun aCancelInSettingsTakesTheWaitingCardAway() {
        grantNotifications()
        poorBag()
        show()
        compose.awaitUntil { vm.readerState.value != null && vm.readerSettings.value != null }
        compose.onNodeWithText("Download (", substring = true).performClick()
        awaitShown(str(R.string.detail_reader_waiting))
        compose.awaitUntil { vm.readerSettings.value?.preferReader == true }
        runBlocking { f.reader.cancel() } // what Settings' Cancel download calls
        awaitGone(str(R.string.detail_reader_waiting))
        assertEquals(false, vm.readerSettings.value!!.preferReader)
    }

    @Test fun yesToTheOfferChoosesTheReaderAndFollowsTheDownload() {
        grantNotifications()
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
