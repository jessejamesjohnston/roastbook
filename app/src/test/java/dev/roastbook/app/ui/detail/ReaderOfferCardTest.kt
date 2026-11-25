package dev.roastbook.app.ui.detail

import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import dev.roastbook.app.reader.DownloadFailure
import dev.roastbook.app.reader.DownloadPhase
import dev.roastbook.app.reader.Eligibility
import dev.roastbook.app.reader.ReaderState
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every state of the reader's card on a coffee's page: what it says, its bar, and its way out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReaderOfferCardTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val total = 1_000_000_000L
    private val clicks = mutableListOf<String>()

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun size(b: Long) = Formatter.formatShortFileSize(context, b)

    private fun state(
        phase: DownloadPhase, bytes: Long = 0, failure: DownloadFailure? = null, metered: Boolean = false, stopped: Boolean = false,
        starting: Boolean = false,
    ) = ReaderState(Eligibility.ELIGIBLE, phase, bytes, total, 0, metered, false, failure, "Test model", stopped, starting)

    private fun show(s: ReaderState, waiting: Boolean = true, explain: Boolean = false) = compose.setContent {
        RoastbookTheme {
            ReaderOfferCard(
                s, waiting,
                onDownload = { clicks += "download" }, onNotNow = { clicks += "notNow" },
                onRetry = { clicks += "retry" }, onCancel = { clicks += "cancel" },
                explainNotifications = explain, onContinue = { clicks += "continue" },
            )
        }
    }

    private fun shown(text: String) = runCatching { compose.onNodeWithText(text).assertExists() }.isSuccess

    private val determinate = SemanticsMatcher("a bar with a value") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let { r -> r != ProgressBarRangeInfo.Indeterminate } == true
    }
    private val indeterminate = SemanticsMatcher("a moving bar") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
    }
    private val anyBar = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun bars(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().size

    @Test fun theOfferSaysHowTheDownloadTravelsForEitherMobileDataChoice() {
        var metered by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            RoastbookTheme { ReaderOfferCard(state(DownloadPhase.NONE, metered = metered), false, {}, {}, {}, {}) }
        }
        compose.onNodeWithText(str(R.string.detail_reader_offer_body, size(total))).assertExists()
        metered = true
        compose.onNodeWithText(str(R.string.detail_reader_offer_body_mobile, size(total))).assertExists()
    }

    @Test fun theOfferCanExplainNotificationsInPlaceOfItsButtons() {
        show(state(DownloadPhase.NONE), waiting = false, explain = true)
        assert(!shown(str(R.string.detail_reader_offer_not_now)))
        compose.onNodeWithText(str(R.string.permission_continue)).performClick()
        assertEquals(listOf("continue"), clicks)
    }

    @Test fun theMomentAfterDownloadSaysItIsStartingWithAMovingBarAndCancel() {
        show(state(DownloadPhase.NONE))
        compose.onNodeWithText(str(R.string.detail_reader_starting)).assertExists()
        assertEquals(1, bars(indeterminate))
        assert(!shown(str(R.string.reader_resume)))
        compose.onNodeWithText(str(R.string.reader_cancel)).performClick()
        assertEquals(listOf("cancel"), clicks)
    }

    @Test fun aRunningDownloadFillsItsBarWithTheBytes() {
        show(state(DownloadPhase.DOWNLOADING, bytes = total / 4))
        compose.onNodeWithText(str(R.string.reader_downloading, size(total / 4), size(total))).assertExists()
        val info = compose.onNode(determinate).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0.25f, info.current, 0.001f)
        compose.onNodeWithText(str(R.string.detail_reader_waiting)).assertExists()
        compose.onNodeWithText(str(R.string.reader_cancel)).performClick()
        assertEquals(listOf("cancel"), clicks)
    }

    @Test fun checkingTheFileShowsAMovingBar() {
        show(state(DownloadPhase.VERIFYING, bytes = total))
        compose.onNodeWithText(str(R.string.reader_verifying)).assertExists()
        assertEquals(1, bars(indeterminate))
        compose.onNodeWithText(str(R.string.reader_cancel)).assertExists()
    }

    @Test fun waitingForANetworkSaysWhichAndHowFarItGot() {
        show(state(DownloadPhase.WAITING_FOR_NETWORK))
        compose.onNodeWithText(str(R.string.reader_waiting_wifi)).assertExists()
        compose.onNodeWithText(str(R.string.reader_cancel)).assertExists()
    }

    @Test fun waitingForAnyConnectionPartWaySaysHowFarItGot() {
        show(state(DownloadPhase.WAITING_FOR_NETWORK, bytes = total / 2, metered = true))
        compose.onNodeWithText(str(R.string.reader_waiting_network_at, size(total / 2), size(total))).assertExists()
        assertEquals(1, bars(determinate))
    }

    @Test fun aPausedDownloadSaysWhereItStoppedWithNoBarAndOffersResumeAndCancel() {
        show(state(DownloadPhase.NONE, bytes = total / 2, stopped = true))
        compose.onNodeWithText(str(R.string.reader_partial, size(total / 2), size(total))).assertExists()
        assert(!shown(str(R.string.reader_downloading, size(total / 2), size(total))))
        assertEquals(0, bars(anyBar))
        compose.onNodeWithText(str(R.string.reader_resume)).performClick()
        compose.onNodeWithText(str(R.string.reader_cancel)).performClick()
        assertEquals(listOf("retry", "cancel"), clicks)
    }

    @Test fun aDownloadStartedAgainOverAFailedOnesPartSaysItIsStarting() {
        show(state(DownloadPhase.NONE, bytes = total / 2, starting = true))
        compose.onNodeWithText(str(R.string.detail_reader_starting)).assertExists()
        assert(!shown(str(R.string.reader_partial, size(total / 2), size(total))))
        assert(!shown(str(R.string.reader_resume)))
        assertEquals(1, bars(indeterminate))
        compose.onNodeWithText(str(R.string.reader_cancel)).performClick()
        assertEquals(listOf("cancel"), clicks)
    }

    @Test fun aPartOnDiskWithNoJobStillReadsAsPaused() {
        show(state(DownloadPhase.NONE, bytes = total / 2))
        compose.onNodeWithText(str(R.string.reader_partial, size(total / 2), size(total))).assertExists()
        compose.onNodeWithText(str(R.string.reader_resume)).assertExists()
        assertEquals(0, bars(anyBar))
    }

    @Test fun aDownloadPausedBeforeAnyBytesArrivedSaysPaused() {
        show(state(DownloadPhase.NONE, stopped = true))
        compose.onNodeWithText(str(R.string.detail_reader_paused)).assertExists()
        compose.onNodeWithText(str(R.string.reader_resume)).assertExists()
        assertEquals(0, bars(anyBar))
    }

    @Test fun aFailedDownloadSaysWhyAsSettingsDoesAndOffersTryAgainAndNotNow() {
        val table = mapOf(
            DownloadFailure.NO_SPACE to str(R.string.reader_failed_space, size(total)),
            DownloadFailure.CHECKSUM to str(R.string.reader_failed_checksum),
            DownloadFailure.SERVER to str(R.string.reader_failed_server),
        )
        var s by androidx.compose.runtime.mutableStateOf(state(DownloadPhase.FAILED, failure = DownloadFailure.NO_SPACE))
        compose.setContent {
            RoastbookTheme {
                ReaderOfferCard(
                    s, true, {}, onNotNow = { clicks += "notNow" }, onRetry = { clicks += "retry" }, onCancel = { clicks += "cancel" },
                )
            }
        }
        for ((failure, text) in table) {
            s = state(DownloadPhase.FAILED, failure = failure)
            compose.onNodeWithText(text).assertExists()
            assertEquals(0, bars(anyBar))
        }
        // Not now only stops this bag waiting; throwing the download away is Settings' Cancel download.
        assert(!shown(str(R.string.reader_cancel)))
        compose.onNodeWithText(str(R.string.reader_retry)).performClick()
        compose.onNodeWithText(str(R.string.detail_reader_offer_not_now)).performClick()
        assertEquals(listOf("retry", "notNow"), clicks)
    }

    @Test fun onceTheReaderIsHereTheCardSaysTheBagIsBeingReadAgain() {
        show(state(DownloadPhase.READY, bytes = total))
        compose.onNodeWithText(str(R.string.detail_reader_rereading)).assertExists()
        assertEquals(1, bars(indeterminate))
        assert(!shown(str(R.string.reader_cancel)))
        assert(!shown(str(R.string.detail_reader_waiting)))
    }

    @Test fun aWaitingCardCanExplainNotificationsBeforeResuming() {
        show(state(DownloadPhase.NONE, bytes = 5, stopped = true), explain = true)
        assert(!shown(str(R.string.reader_resume)))
        compose.onNodeWithText(str(R.string.permission_continue)).performClick()
        assertEquals(listOf("continue"), clicks)
    }
}
