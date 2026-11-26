package dev.roastbook.app.ui.onboarding

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.viewModelScope
import dev.roastbook.app.R
import dev.roastbook.app.extraction.NanoStatus
import dev.roastbook.app.reader.FakeNano
import dev.roastbook.app.reader.ModelSpec
import dev.roastbook.app.reader.ReaderHarness
import dev.roastbook.app.reader.ReaderOffer
import dev.roastbook.app.ui.theme.RoastbookTheme
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The onboarding sentence for phones with no built-in AI to read bags. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReaderNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val h = ReaderHarness()
    private var vm: ReaderNoticeViewModel? = null

    @After fun tearDown() {
        vm?.viewModelScope?.cancel()
        h.close()
    }

    private fun show(nano: FakeNano, totalMem: Long = 8 * ReaderHarness.GIB, abis: List<String> = listOf("arm64-v8a")): ReaderNoticeViewModel {
        h.device(totalMem, abis)
        val model = ReaderNoticeViewModel(nano, h.manager())
        vm = model
        compose.setContent { RoastbookTheme { Column { ReaderNotice(model) } } }
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); nano.asked > 0 }
        compose.waitForIdle()
        return model
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private val downloadText get() = h.context.getString(
        R.string.onboarding_reader_download, dev.roastbook.app.util.English.fileSize(ModelSpec.GEMMA_4_E2B.sizeBytes))
    private val cloudText get() = h.context.getString(R.string.onboarding_reader_cloud)

    @Test fun aCapablePhoneWithoutBuiltInAiHearsAboutTheDownloadAndItsSize() {
        val model = show(FakeNano(NanoStatus.UNAVAILABLE))
        assertEquals(ReaderOffer.OFFER, model.offer.value)
        assertEquals(ModelSpec.GEMMA_4_E2B.sizeBytes, model.modelSize)
        compose.onNodeWithText(downloadText).assertIsDisplayed()
    }

    @Test fun aStatusCallThatThrowsCountsAsNoBuiltInAi() {
        show(FakeNano { error("no AICore") })
        compose.onNodeWithText(downloadText).assertIsDisplayed()
    }

    @Test fun aPhoneWithTooLittleMemoryIsPointedToTheCloudKey() {
        val model = show(FakeNano(NanoStatus.UNAVAILABLE), totalMem = 4 * ReaderHarness.GIB)
        assertEquals(ReaderOffer.CANT_MEMORY, model.offer.value)
        compose.onNodeWithText(cloudText).assertIsDisplayed()
        assertFalse(shown(downloadText))
    }

    @Test fun aPhoneWithAnUnsupportedCpuIsPointedToTheCloudKey() {
        val model = show(FakeNano(NanoStatus.UNAVAILABLE), abis = listOf("armeabi-v7a"))
        assertEquals(ReaderOffer.CANT_CPU, model.offer.value)
        compose.onNodeWithText(cloudText).assertIsDisplayed()
    }

    @Test fun nothingIsSaidWhereTheBuiltInAiReadsBagsOrIsOnItsWay() {
        for (status in listOf(NanoStatus.AVAILABLE, NanoStatus.DOWNLOADABLE)) {
            h.device()
            val nano = FakeNano(status)
            val model = ReaderNoticeViewModel(nano, h.manager())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, nano.asked)
            assertNull(status.name, model.offer.value)
            model.viewModelScope.cancel()
        }
        val model = show(FakeNano(NanoStatus.AVAILABLE))
        assertNull(model.offer.value)
        assertFalse(shown(downloadText))
        assertFalse(shown(cloudText))
    }
}
