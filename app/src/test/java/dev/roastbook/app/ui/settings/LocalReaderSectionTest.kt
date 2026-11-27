package dev.roastbook.app.ui.settings

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.roastbook.app.R
import dev.roastbook.app.extraction.NanoStatus
import dev.roastbook.app.reader.FakeNano
import dev.roastbook.app.reader.LiteRtLocalReader
import dev.roastbook.app.reader.LocalReaderManager
import dev.roastbook.app.reader.ModelDownloadWorker
import dev.roastbook.app.reader.ReaderHarness
import dev.roastbook.app.reader.ScriptedWorker
import dev.roastbook.app.ui.components.PermissionAsks
import dev.roastbook.app.ui.theme.RoastbookTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings' on-device reader card: what each download state says and what each control does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalReaderSectionTest {
    @get:Rule val compose = createComposeRule()
    private val h = ReaderHarness()
    private val gate = CompletableDeferred<Unit>()
    private var vm: LocalReaderViewModel? = null

    private fun str(id: Int, vararg args: Any) = h.context.getString(id, *args)
    private fun size(bytes: Long) = dev.roastbook.app.util.English.fileSize(bytes)
    private val total = ReaderHarness.MODEL_BYTES.size.toLong()

    /** Permission dialogs the card asked Android for, and the answer each one gets. */
    private val asked = mutableListOf<String>()
    private var grantOnAsk = true
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                asked += input.toString()
                if (grantOnAsk) grantNotifications()
                dispatchResult(requestCode, grantOnAsk)
            }
        }
    }

    @After fun tearDown() {
        gate.complete(Unit)
        vm?.viewModelScope?.cancel()
        h.close()
    }

    private fun grantNotifications() = shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun manager(
        totalMem: Long = 8 * ReaderHarness.GIB, abis: List<String> = listOf("arm64-v8a"), startingGrace: Long = 0L,
    ): LocalReaderManager {
        h.device(totalMem, abis)
        h.debugSpec()
        return h.manager(startingGrace)
    }

    private fun show(m: LocalReaderManager, nano: FakeNano = FakeNano(NanoStatus.UNAVAILABLE)): LocalReaderViewModel {
        val model = LocalReaderViewModel(h.context, m, LiteRtLocalReader(m), h.prefs, nano)
        vm = model
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                RoastbookTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LocalReaderSection(model) } }
            }
        }
        return model
    }

    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitGone(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    /** Waits for a condition outside the UI tree, letting main-thread work (the ViewModel's launches) run meanwhile. */
    private fun waitUntil(what: () -> Boolean) = compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); what() }

    @Test fun nothingIsDrawnWhereTheDevicesOwnAiReadsBagsAndTheReaderCannotRun() {
        val nano = FakeNano(NanoStatus.AVAILABLE)
        val model = show(manager(totalMem = 4 * ReaderHarness.GIB), nano)
        waitUntil { model.nanoChecked.value && model.state.value != null }
        compose.waitForIdle()
        assertFalse(shown(str(R.string.reader_title)))
        assertFalse(shown(str(R.string.reader_cant_title)))
        assertEquals(1, nano.asked)
    }

    @Test fun whereTheDevicesOwnAiWorksTheReaderIsOfferedWithTheChoice() = runBlocking {
        val model = show(manager(), FakeNano(NanoStatus.AVAILABLE))
        waitFor(str(R.string.reader_download, size(total)))
        assertTrue(shown(str(R.string.reader_explainer, size(total))))
        assertFalse(shown(str(R.string.reader_nano_failing)))
        // The device default stays: the built-in AI reads until the person turns this on.
        assertTrue(shown(str(R.string.reader_prefer)))
        assertFalse(h.prefs.readerPreferred())
        click(str(R.string.reader_prefer))
        waitUntil { model.settings.value?.preferReader == true }
        assertTrue(h.prefs.readerPreferred())
    }

    @Test fun withoutABuiltInAiThereIsNoChoiceToMake() {
        show(manager())
        waitFor(str(R.string.reader_download, size(total)))
        assertFalse(shown(str(R.string.reader_prefer)))
    }

    @Test fun aDeviceWithTooLittleMemoryIsToldHowMuchItHas() {
        val model = show(manager(totalMem = 4 * ReaderHarness.GIB))
        waitFor(str(R.string.reader_title))
        assertEquals(4 * ReaderHarness.GIB, model.totalMem)
        assertTrue(shown(str(R.string.reader_cant_title)))
        assertTrue(shown(str(R.string.reader_cant_memory, size(4 * ReaderHarness.GIB))))
        assertFalse(shown(str(R.string.reader_download, size(total))))
    }

    @Test fun anUnsupportedCpuIsToldSo() {
        show(manager(abis = listOf("armeabi-v7a")))
        waitFor(str(R.string.reader_cant_cpu))
        assertTrue(shown(str(R.string.reader_cant_title)))
        assertFalse(shown(str(R.string.reader_allow_mobile)))
    }

    @Test fun theOfferNamesTheSizeAndDownloadStartsAWaitingJob() {
        grantNotifications()
        val m = manager()
        show(m)
        waitFor(str(R.string.reader_download, size(total)))
        assertTrue(shown(str(R.string.reader_explainer, size(total))))
        assertTrue(shown(str(R.string.reader_model_credit)))
        assertTrue(shown(str(R.string.reader_allow_mobile)))
        click(str(R.string.reader_download, size(total)))
        waitFor(str(R.string.reader_waiting_wifi))
        assertEquals(WorkInfo.State.ENQUEUED, h.pendingJob().state)
        assertTrue(shown(str(R.string.reader_pause)))
        assertTrue(shown(str(R.string.reader_cancel)))
        // Allowed notifications: no "progress shows only here" line, and no permission dialog.
        assertFalse(shown(str(R.string.reader_notif_denied)))
        assertTrue(asked.isEmpty())
    }

    @Test fun theMobileDataSwitchRequeuesTheWaitingDownload() = runBlocking {
        grantNotifications()
        val m = manager()
        show(m)
        waitFor(str(R.string.reader_download, size(total)))
        click(str(R.string.reader_download, size(total)))
        waitFor(str(R.string.reader_waiting_wifi))
        click(str(R.string.reader_allow_mobile))
        waitFor(str(R.string.reader_waiting_network))
        assertTrue(h.prefs.current().allowMetered)
        // The preference (and so the line) changes first; the job is replaced after a hop to IO, and
        // for a moment the old and new jobs can both be listed. Wait for the replacement.
        waitUntil { runCatching { h.pendingJob().constraints.requiredNetworkType == NetworkType.CONNECTED }.getOrDefault(false) }
    }

    @Test fun aWaitingDownloadWithBytesSaysHowFarItGot() = runBlocking {
        grantNotifications()
        val m = manager()
        h.writePart(m, 20)
        m.download()
        show(m)
        waitFor(str(R.string.reader_waiting_wifi_at, size(20), size(total)))
        click(str(R.string.reader_allow_mobile))
        waitFor(str(R.string.reader_waiting_network_at, size(20), size(total)))
    }

    @Test fun pauseKeepsTheBytesAndCancelThrowsThemAway() = runBlocking {
        grantNotifications()
        val m = manager()
        h.writePart(m, 20)
        m.download()
        show(m)
        waitFor(str(R.string.reader_pause))
        click(str(R.string.reader_pause))
        waitFor(str(R.string.reader_partial, size(20), size(total)))
        assertTrue(h.jobs().all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(shown(str(R.string.reader_resume)))
        assertEquals(20L, m.store.part.length())
        click(str(R.string.reader_cancel))
        waitFor(str(R.string.reader_download, size(total)))
        assertFalse(m.store.part.exists())
        assertFalse(shown(str(R.string.reader_cancel)))
    }

    @Test fun resumeContinuesAPausedDownload() {
        grantNotifications()
        val m = manager()
        h.writePart(m, 20)
        show(m)
        waitFor(str(R.string.reader_resume))
        click(str(R.string.reader_resume))
        waitFor(str(R.string.reader_waiting_wifi_at, size(20), size(total)))
        assertEquals(WorkInfo.State.ENQUEUED, h.pendingJob().state)
    }

    @Test fun aRunningDownloadShowsItsBytesAndCancelStopsIt() {
        grantNotifications()
        val m = manager()
        h.worker = { c, p ->
            ScriptedWorker(c, p) {
                h.writePart(m, 40)
                setProgress(workDataOf(ModelDownloadWorker.KEY_PHASE to "DOWNLOADING", ModelDownloadWorker.KEY_BYTES to 40L))
                gate.await(); Result.success()
            }
        }
        runBlocking { m.download() }
        h.runJob()
        show(m)
        waitFor(str(R.string.reader_downloading, size(40), size(total)))
        assertTrue(shown(str(R.string.reader_pause)))
        click(str(R.string.reader_cancel))
        waitFor(str(R.string.reader_download, size(total)))
        assertFalse(m.store.part.exists())
        assertTrue(h.jobs().all { it.state.isFinished })
    }

    /**
     * The states a device reports after Download, in order: the job enqueued, running before it
     * reported anything, running with its first report of 0 bytes, then running with bytes. Until a
     * byte has arrived the section says the download is starting over a moving bar; the bar fills
     * only from the first bytes. (Showing "Downloading: 0 B of 2.6 GB" over an empty bar read as stuck.)
     */
    @Test fun untilTheFirstBytesArriveTheDownloadSaysItIsStartingOverAMovingBar() {
        grantNotifications()
        val m = manager()
        val reported = CompletableDeferred<Unit>()
        val bytes = CompletableDeferred<Unit>()
        h.worker = { c, p ->
            ScriptedWorker(c, p) {
                reported.await()
                setProgress(workDataOf(ModelDownloadWorker.KEY_PHASE to "DOWNLOADING", ModelDownloadWorker.KEY_BYTES to 0L))
                bytes.await()
                h.writePart(m, 16)
                setProgress(workDataOf(ModelDownloadWorker.KEY_PHASE to "DOWNLOADING", ModelDownloadWorker.KEY_BYTES to 16L))
                gate.await(); Result.success()
            }
        }
        runBlocking { m.download() }
        show(m)
        val starting = str(R.string.detail_reader_starting)
        val zero = str(R.string.reader_downloading, size(0), size(total))
        // Enqueued, its network rule not met yet: an honest wait, not a start.
        waitFor(str(R.string.reader_waiting_wifi))
        // Running, nothing reported yet.
        h.runJob()
        waitFor(starting)
        assertFalse(shown(zero))
        assertEquals(1, bars(indeterminate)); assertEquals(0, bars(determinate))
        // Running, first report: 0 bytes.
        reported.complete(Unit)
        waitUntil { h.jobs().any { it.state == WorkInfo.State.RUNNING && it.progress.keyValueMap.isNotEmpty() } }
        compose.waitForIdle()
        assertTrue(shown(starting))
        assertFalse(shown(zero))
        assertEquals(1, bars(indeterminate)); assertEquals(0, bars(determinate))
        assertTrue("Pause and Cancel are there while it starts", shown(str(R.string.reader_pause)) && shown(str(R.string.reader_cancel)))
        // Running with bytes: the line counts them and the bar fills.
        bytes.complete(Unit)
        waitFor(str(R.string.reader_downloading, size(16), size(total)))
        assertFalse(shown(starting))
        assertEquals(1, bars(determinate)); assertEquals(0, bars(indeterminate))
    }

    /** The device's first state after Download, a job just enqueued, reads as starting too. */
    @Test fun aJustEnqueuedDownloadSaysItIsStartingNotWaitingForWifi() {
        grantNotifications()
        val m = manager(startingGrace = 60_000L)
        runBlocking { m.download() }
        show(m)
        waitFor(str(R.string.detail_reader_starting))
        assertFalse(shown(str(R.string.reader_waiting_wifi)))
        assertEquals(1, bars(indeterminate)); assertEquals(0, bars(determinate))
    }

    private val determinate = SemanticsMatcher("a bar with a value") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let { r -> r != ProgressBarRangeInfo.Indeterminate } == true
    }
    private val indeterminate = SemanticsMatcher("a moving bar") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
    }
    private fun bars(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().size

    @Test fun theCheckCannotBePausedOnlyCanceled() {
        grantNotifications()
        val m = manager()
        h.worker = { c, p ->
            ScriptedWorker(c, p) {
                setProgress(workDataOf(ModelDownloadWorker.KEY_PHASE to "VERIFYING", ModelDownloadWorker.KEY_BYTES to 0L))
                gate.await(); Result.success()
            }
        }
        runBlocking { m.download() }
        h.runJob()
        show(m)
        waitFor(str(R.string.reader_verifying))
        assertFalse(shown(str(R.string.reader_pause)))
        assertTrue(shown(str(R.string.reader_cancel)))
    }

    @Test fun withNotificationsRefusedTheCardSaysProgressShowsOnlyHere() {
        val m = manager()
        // Asked before, so the download starts straight away without explaining again.
        PermissionAsks.markAsked(h.context, Manifest.permission.POST_NOTIFICATIONS)
        show(m)
        waitFor(str(R.string.reader_download, size(total)))
        click(str(R.string.reader_download, size(total)))
        waitFor(str(R.string.reader_notif_denied))
        assertTrue(shown(str(R.string.reader_waiting_wifi)))
        assertTrue(asked.isEmpty())
    }

    @Test fun theFirstDownloadExplainsNotificationsBeforeAndroidAsks() {
        val m = manager()
        show(m)
        waitFor(str(R.string.reader_download, size(total)))
        click(str(R.string.reader_download, size(total)))
        waitFor(str(R.string.notif_why_download))
        // The reason is a dialog; nothing starts until Continue (or Not now).
        compose.onNode(androidx.compose.ui.test.isDialog()).assertExists()
        assertTrue(h.jobs().isEmpty())
        assertTrue(asked.isEmpty())
        compose.onNodeWithText(str(R.string.permission_continue)).performClick()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asked)
        waitFor(str(R.string.reader_waiting_wifi))
        assertFalse(shown(str(R.string.notif_why_download)))
        assertFalse(shown(str(R.string.reader_notif_denied)))
        assertTrue(PermissionAsks.asked(h.context, Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test fun aRefusedNotificationDialogStillStartsTheDownload() {
        grantOnAsk = false
        val m = manager()
        show(m)
        waitFor(str(R.string.reader_download, size(total)))
        click(str(R.string.reader_download, size(total)))
        waitFor(str(R.string.permission_continue))
        compose.onNodeWithText(str(R.string.permission_continue)).performClick()
        waitFor(str(R.string.reader_waiting_wifi))
        assertTrue(shown(str(R.string.reader_notif_denied)))
        assertEquals(WorkInfo.State.ENQUEUED, h.pendingJob().state)
    }

    /**
     * After the reader's first read its runtime has saved a cache beside the model, so the size on
     * disk grows past the download size. The line says how much of it is that cache, and Delete
     * removes the cache with the model.
     */
    @Test fun theSizeOnDiskNamesTheRuntimesCacheAndDeleteRemovesIt() {
        val m = manager()
        h.installModel(m)
        show(m)
        waitFor(str(R.string.reader_ready, size(total + 64)))
        java.io.File(m.store.cacheDir, "weights.cache").writeBytes(ByteArray(40))
        m.onDiskChanged()
        waitFor(str(R.string.reader_ready_cache, size(total + 64 + 40), size(40)))
        click(str(R.string.reader_delete))
        waitFor(str(R.string.reader_delete_title))
        compose.onNodeWithText(str(R.string.reader_delete_yes)).performClick()
        waitFor(str(R.string.reader_download, size(total)))
        assertFalse("the model, its stamp and the cache are all gone", m.store.root.exists())
    }

    @Test fun aReadyReaderShowsItsSizeAndDeleteAsksFirst() {
        val m = manager()
        h.installModel(m)
        show(m)
        waitFor(str(R.string.reader_ready, size(total + 64)))
        // Nothing left to download, so no network rule.
        assertFalse(shown(str(R.string.reader_allow_mobile)))
        click(str(R.string.reader_delete))
        waitFor(str(R.string.reader_delete_title))
        assertTrue(shown(str(R.string.reader_delete_confirm)))
        compose.onNodeWithText(str(R.string.detail_cancel)).performClick()
        waitGone(str(R.string.reader_delete_title))
        assertTrue(m.isReady())
        click(str(R.string.reader_delete))
        waitFor(str(R.string.reader_delete_yes))
        compose.onNodeWithText(str(R.string.reader_delete_yes)).performClick()
        waitFor(str(R.string.reader_download, size(total)))
        assertFalse(m.isReady())
        assertFalse(m.store.root.exists())
    }

    @Test fun eachFailedDownloadSaysWhyAndOffersTryAgainAndCancelOnceThereIsAPart() {
        grantNotifications()
        val m = manager()
        val table = listOf(
            "NO_SPACE" to str(R.string.reader_failed_space, size(total)),
            "HASH_MISMATCH" to str(R.string.reader_failed_checksum),
            "HTTP" to str(R.string.reader_failed_server),
        )
        var reason = table.first().first
        h.worker = { c, p -> ScriptedWorker(c, p) { Result.failure(workDataOf(ModelDownloadWorker.KEY_FAILURE to reason)) } }
        show(m)
        waitFor(str(R.string.reader_download, size(total)))
        click(str(R.string.reader_download, size(total)))
        for ((i, row) in table.withIndex()) {
            reason = row.first
            if (i > 0) click(str(R.string.reader_retry))
            waitUntil { h.jobs().any { !it.state.isFinished } }
            h.runJob()
            waitFor(row.second)
            assertTrue(shown(str(R.string.reader_retry)))
        }
        // Nothing on disk, nothing to throw away.
        assertFalse(shown(str(R.string.reader_cancel)))
        // With part of the file on disk, Cancel download removes it and the failure with it.
        h.writePart(m, 20)
        m.onDiskChanged()
        waitFor(str(R.string.reader_cancel))
        click(str(R.string.reader_cancel))
        waitFor(str(R.string.reader_download, size(total)))
        assertFalse(m.store.part.exists())
        assertFalse(shown(table.last().second))
    }

    @Test fun aFailingBuiltInAiIsNamedAsTheReasonForTheOffer() = runBlocking {
        h.prefs.recordNano(ok = false, reason = "model missing")
        // Nano says it's available, but it failed here: the reader is offered anyway.
        show(manager(), FakeNano(NanoStatus.AVAILABLE))
        waitFor(str(R.string.reader_nano_failing))
        assertTrue(shown(str(R.string.reader_download, size(total))))
    }

    @Test fun readerFilesOnDiskShowTheirControlsBeforeTheBuiltInAiAnswers() {
        val m = manager()
        h.writePart(m, 20)
        val never = CompletableDeferred<NanoStatus>()
        val nano = FakeNano { never.await() }
        val model = show(m, nano)
        waitFor(str(R.string.reader_partial, size(20), size(total)))
        assertFalse(model.nanoChecked.value)
        never.complete(NanoStatus.AVAILABLE)
    }
}
