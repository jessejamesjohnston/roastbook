package dev.roastbook.app.reader

import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The reader model's download job and the state Settings draws from it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalReaderManagerTest {
    private val h = ReaderHarness()
    private val gate = CompletableDeferred<Unit>()

    @After fun tearDown() {
        gate.complete(Unit)
        h.close()
    }

    private fun eligibleManager(): LocalReaderManager {
        h.device()
        h.debugSpec()
        return h.manager()
    }

    @Test fun eligibilityComesFromTheDevicesMemoryAndCpu() {
        h.debugSpec()
        val table = listOf(
            Triple(8 * ReaderHarness.GIB, listOf("arm64-v8a"), Eligibility.ELIGIBLE),
            Triple(4 * ReaderHarness.GIB, listOf("arm64-v8a"), Eligibility.LOW_MEMORY),
            Triple(12 * ReaderHarness.GIB, listOf("armeabi-v7a"), Eligibility.UNSUPPORTED_CPU),
        )
        for ((mem, abis, want) in table) {
            h.device(mem, abis)
            assertEquals("$mem $abis", want, h.manager().eligibility)
        }
    }

    @Test fun aDeviceThatCannotRunTheReaderNeverStartsADownloadOrUsesAModel() = runBlocking {
        h.device(totalMem = 4 * ReaderHarness.GIB)
        h.debugSpec()
        val m = h.manager()
        h.installModel(m)
        m.download()
        assertTrue(h.jobs().isEmpty())
        // The file is there, but this device can't run it.
        assertFalse(m.isReady())
    }

    @Test fun aDownloadWaitsForWifiByDefault() = runBlocking {
        val m = eligibleManager()
        m.download()
        val job = h.pendingJob()
        assertEquals(WorkInfo.State.ENQUEUED, job.state)
        assertEquals(NetworkType.UNMETERED, job.constraints.requiredNetworkType)
        assertTrue(job.constraints.requiresStorageNotLow())
        assertEquals(DownloadPhase.WAITING_FOR_NETWORK, h.awaitState(m) { it.phase == DownloadPhase.WAITING_FOR_NETWORK }.phase)
    }

    @Test fun aDownloadUsesMobileDataWhenThePersonAllowedIt() = runBlocking {
        val m = eligibleManager()
        h.prefs.setAllowMetered(true)
        m.download()
        assertEquals(NetworkType.CONNECTED, h.pendingJob().constraints.requiredNetworkType)
        assertTrue(h.awaitState(m) { it.phase == DownloadPhase.WAITING_FOR_NETWORK }.allowMetered)
    }

    @Test fun aSecondDownloadTapKeepsTheJobAlreadyWaiting() = runBlocking {
        val m = eligibleManager()
        m.download()
        val first = h.pendingJob().id
        m.download()
        assertEquals(first, h.pendingJob().id)
        assertEquals(1, h.jobs().size)
    }

    @Test fun aReadyModelIsNotDownloadedAgain() = runBlocking {
        val m = eligibleManager()
        h.installModel(m)
        assertTrue(m.isReady())
        m.download()
        assertTrue(h.jobs().isEmpty())
        val s = h.awaitState(m) { it.phase == DownloadPhase.READY }
        assertEquals(ReaderHarness.MODEL_BYTES.size + 64L, s.sizeOnDisk) // the model plus its digest stamp
        assertNull(s.failure)
    }

    @Test fun turningOnMobileDataRequeuesAWaitingDownloadWithTheNewRule() = runBlocking {
        val m = eligibleManager()
        m.download()
        val waiting = h.pendingJob().id
        m.setAllowMetered(true)
        val now = h.pendingJob()
        assertNotEquals(waiting, now.id)
        assertEquals(NetworkType.CONNECTED, now.constraints.requiredNetworkType)
        assertTrue(h.prefs.current().allowMetered)
        // And back off again: Wi-Fi only.
        m.setAllowMetered(false)
        assertEquals(NetworkType.UNMETERED, h.pendingJob().constraints.requiredNetworkType)
    }

    @Test fun theMobileDataSwitchAloneStartsNothing() = runBlocking {
        val m = eligibleManager()
        m.setAllowMetered(true)
        assertTrue(h.jobs().isEmpty())
        assertTrue(h.prefs.current().allowMetered)
    }

    @Test fun pauseStopsTheJobAndKeepsThePartialFile() = runBlocking {
        val m = eligibleManager()
        m.download()
        h.writePart(m, 20)
        m.pause()
        assertTrue(h.jobs().all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(20L, m.store.part.length())
        val s = h.awaitState(m) { it.phase == DownloadPhase.NONE }
        assertEquals(20L, s.downloadedBytes)
        assertTrue("stopped part-way, not never started", s.stopped)
        assertTrue(ReaderOffer.hasReaderFiles(s))
    }

    @Test fun pauseKeepsTheBagsWaitingAndTheReaderChosen() = runBlocking {
        val m = eligibleManager()
        h.prefs.acceptOffer("c1")
        m.download()
        m.pause()
        assertEquals(setOf("c1"), h.prefs.current().pendingRereads)
        assertTrue(h.prefs.current().preferReader)
    }

    @Test fun aCanceledDownloadIsNotAPausedOne() = runBlocking {
        // WorkManager leaves a canceled job CANCELLED, exactly as Pause does. Until a new job is
        // reported, Download on the next bag's offer must read as starting, not as paused.
        val m = eligibleManager()
        h.prefs.acceptOffer("c1")
        m.download()
        m.cancel()
        assertTrue(h.jobs().all { it.state == WorkInfo.State.CANCELLED })
        val s = h.awaitState(m) { it.phase == DownloadPhase.NONE && it.downloadedBytes == 0L }
        assertFalse("canceled, not paused", s.stopped)
    }

    @Test fun aDeletedReaderLeavesNoPausedDownload() = runBlocking {
        val m = eligibleManager()
        m.download()
        h.writePart(m, 20)
        m.delete()
        assertFalse(h.awaitState(m) { it.phase == DownloadPhase.NONE && it.downloadedBytes == 0L }.stopped)
    }

    @Test fun aPauseAfterAnEarlierCancelIsAPause() = runBlocking {
        val m = eligibleManager()
        m.download()
        m.cancel()
        m.download()
        h.awaitState(m) { it.phase == DownloadPhase.WAITING_FOR_NETWORK }
        m.pause()
        assertTrue(h.awaitState(m) { it.phase == DownloadPhase.NONE }.stopped)
    }

    @Test fun theNotificationsPauseIsAPause() = runBlocking {
        // The notification's Pause cancels the job by its id, without going through the manager.
        val m = eligibleManager()
        m.download()
        h.workManager.cancelWorkById(h.pendingJob().id).result.get()
        assertTrue(h.awaitState(m) { it.phase == DownloadPhase.NONE }.stopped)
    }

    @Test fun aDownloadNeverStartedIsNotStopped() = runBlocking {
        assertFalse(h.awaitState(eligibleManager()) { true }.stopped)
    }

    @Test fun cancelStopsTheJobAndRemovesThePartialFile() = runBlocking {
        val m = eligibleManager()
        h.prefs.acceptOffer("c1")
        m.download()
        h.writePart(m, 20)
        m.cancel()
        assertTrue(h.jobs().all { it.state == WorkInfo.State.CANCELLED })
        assertFalse(m.store.part.exists())
        // No bag waits for a reader that isn't coming, and the switch no longer chooses it.
        assertEquals(emptySet<String>(), h.prefs.current().pendingRereads)
        assertFalse(h.prefs.current().preferReader)
        assertEquals(0L, h.awaitState(m) { it.phase == DownloadPhase.NONE && it.downloadedBytes == 0L }.downloadedBytes)
    }

    @Test fun cancelRemovesThePartOnlyOnceTheStoppingDownloadHasLetGoOfIt() = runBlocking {
        // Cancel lands while the download is waiting for the server's answer. WorkManager stops the
        // job a moment later, but by then the download has opened the part again and written to it.
        val server = okhttp3.mockwebserver.MockWebServer()
        val asked = java.util.concurrent.CountDownLatch(1)
        val answer = java.util.concurrent.CountDownLatch(1)
        val bytes = ReaderHarness.MODEL_BYTES
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                asked.countDown()
                answer.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return okhttp3.mockwebserver.MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 20-${bytes.size - 1}/${bytes.size}")
                    .setBody(okio.Buffer().write(bytes.copyOfRange(20, bytes.size)))
                    .throttleBody(4, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        server.start()
        try {
            h.device()
            h.debugSpec(url = server.url("/model.bin").toString())
            val m = h.manager()
            h.writePart(m, 20)
            val download = launch(kotlinx.coroutines.Dispatchers.IO) { ModelDownloader(okhttp3.OkHttpClient()).download(m.spec, m.store) }
            assertTrue(asked.await(5, java.util.concurrent.TimeUnit.SECONDS))
            m.cancel()
            answer.countDown()
            kotlinx.coroutines.delay(300) // a few bytes arrive before the job is stopped
            download.cancel()
            download.join()
            val deadline = System.currentTimeMillis() + 5_000
            while (m.store.part.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertFalse("a canceled download leaves no part (${m.store.part.length()} bytes)", m.store.part.exists())
            assertFalse(m.isReady())
        } finally {
            answer.countDown()
            server.shutdown()
        }
    }

    @Test fun deleteRemovesTheModelThePartAndTheRuntimeCache() = runBlocking {
        val m = eligibleManager()
        h.installModel(m)
        File(m.store.cacheDir, "program.bin").writeBytes(ByteArray(10))
        h.awaitState(m) { it.phase == DownloadPhase.READY }
        h.prefs.setPreferReader(true)
        m.delete()
        assertFalse("the delete dialog promises the built-in AI reads new bags", h.prefs.current().preferReader)
        assertFalse(m.isReady())
        assertFalse(m.store.root.exists())
        val s = h.awaitState(m) { it.phase == DownloadPhase.NONE }
        assertEquals(0L, s.sizeOnDisk)
        assertFalse(ReaderOffer.hasReaderFiles(s))
    }

    @Test fun aDiskChangeRefreshesTheSizeOnDisk() = runBlocking {
        val m = eligibleManager()
        assertEquals(0L, h.awaitState(m) { true }.sizeOnDisk)
        m.store.ensureDirs()
        File(m.store.cacheDir, "program.bin").writeBytes(ByteArray(10))
        m.onDiskChanged()
        assertEquals(10L, h.awaitState(m) { it.sizeOnDisk == 10L }.sizeOnDisk)
    }

    @Test fun aRunningJobShowsItsReportedBytesThenTheCheck() = runBlocking {
        val m = eligibleManager()
        val verifying = CompletableDeferred<Unit>()
        h.worker = { c, p ->
            ScriptedWorker(c, p) {
                setProgress(workDataOf(ModelDownloadWorker.KEY_PHASE to "DOWNLOADING", ModelDownloadWorker.KEY_BYTES to 40L))
                verifying.await()
                setProgress(workDataOf(ModelDownloadWorker.KEY_PHASE to "VERIFYING", ModelDownloadWorker.KEY_BYTES to 0L))
                gate.await()
                Result.success()
            }
        }
        m.download()
        h.runJob()
        val downloading = h.awaitState(m) { it.phase == DownloadPhase.DOWNLOADING && it.downloadedBytes == 40L }
        assertEquals(ReaderHarness.MODEL_BYTES.size.toLong(), downloading.totalBytes)
        verifying.complete(Unit)
        h.awaitState(m) { it.phase == DownloadPhase.VERIFYING }
        // The job finished but left no model (say it was deleted underneath): nothing to show.
        gate.complete(Unit)
        assertEquals(DownloadPhase.NONE, h.awaitState(m) { it.phase == DownloadPhase.NONE }.phase)
    }

    @Test fun aRunningJobWithNoReportYetCountsThePartOnDisk() = runBlocking {
        val m = eligibleManager()
        h.writePart(m, 12)
        h.worker = { c, p -> ScriptedWorker(c, p) { gate.await(); Result.success() } }
        m.download()
        h.runJob()
        assertEquals(12L, h.awaitState(m) { it.phase == DownloadPhase.DOWNLOADING }.downloadedBytes)
    }

    @Test fun aFinishedDownloadIsReady() = runBlocking {
        val m = eligibleManager()
        h.worker = { c, p -> ScriptedWorker(c, p) { h.installModel(m); m.onDiskChanged(); Result.success() } }
        m.download()
        h.runJob()
        assertEquals(DownloadPhase.READY, h.awaitState(m) { it.phase == DownloadPhase.READY }.phase)
        assertTrue(m.isReady())
    }

    @Test fun eachFailureReasonReadsAsOnePlainCause() = runBlocking {
        val m = eligibleManager()
        val table = listOf(
            "NO_SPACE" to DownloadFailure.NO_SPACE,
            "HASH_MISMATCH" to DownloadFailure.CHECKSUM,
            "SIZE_MISMATCH" to DownloadFailure.CHECKSUM,
            "NETWORK" to DownloadFailure.SERVER,
            "HTTP" to DownloadFailure.SERVER,
        )
        for ((reason, want) in table) {
            h.worker = { c, p -> ScriptedWorker(c, p) { Result.failure(workDataOf(ModelDownloadWorker.KEY_FAILURE to reason)) } }
            m.download()
            val id = h.pendingJob().id
            h.runJob()
            val s = h.awaitState(m) { s -> s.phase == DownloadPhase.FAILED && h.jobs().last().id == id && s.failure == want }
            assertEquals(reason, want, s.failure)
        }
    }

    private suspend fun failedDownload(m: LocalReaderManager, partBytes: Int): java.util.UUID {
        h.worker = { c, p -> ScriptedWorker(c, p) { Result.failure(workDataOf(ModelDownloadWorker.KEY_FAILURE to "NETWORK")) } }
        m.acceptOffer("c1")
        h.writePart(m, partBytes)
        val id = h.pendingJob().id
        h.runJob()
        h.awaitState(m) { it.phase == DownloadPhase.FAILED }
        return id
    }

    @Test fun notNowOnAFailedDownloadKeepsThePartTheSwitchAndTheFailure() = runBlocking {
        val m = eligibleManager()
        failedDownload(m, 20)
        h.prefs.declineOffer("c1")
        assertEquals(20L, m.store.part.length())
        assertTrue(h.prefs.current().preferReader)
        assertEquals(emptySet<String>(), h.prefs.current().pendingRereads)
        val s = h.awaitState(m) { it.phase == DownloadPhase.FAILED }
        assertEquals("Settings still says why", DownloadFailure.SERVER, s.failure)
        assertEquals(20L, s.downloadedBytes)
    }

    @Test fun acceptingTheNextOfferAfterAFailureSaysTheDownloadIsStarting() = runBlocking {
        val m = eligibleManager()
        val failed = failedDownload(m, 0)
        h.prefs.declineOffer("c1")
        // The moment between the yes and WorkManager reporting the new job.
        h.prefs.acceptOffer("c2", supersededJobs = setOf(failed.toString()))
        val s = h.awaitState(m) { it.phase != DownloadPhase.FAILED }
        assertEquals(DownloadPhase.NONE, s.phase)
        assertNull(s.failure)
        assertFalse(s.stopped)
    }

    @Test fun acceptAnOfferSetsAsideTheFailedJobAndStartsANewOne() = runBlocking {
        val m = eligibleManager()
        val failed = failedDownload(m, 0)
        h.prefs.declineOffer("c1")
        m.acceptOffer("c2")
        assertTrue(failed.toString() in h.prefs.current().discardedJobs)
        assertEquals(setOf("c2"), h.prefs.current().pendingRereads)
        assertNotEquals(failed, h.pendingJob().id)
        assertEquals(DownloadPhase.WAITING_FOR_NETWORK, h.awaitState(m) { it.phase == DownloadPhase.WAITING_FOR_NETWORK }.phase)
    }

    @Test fun aNewDownloadThatFailsAfterAnAcceptedOfferShowsItsOwnReason() = runBlocking {
        val m = eligibleManager()
        val failed = failedDownload(m, 20)
        h.prefs.declineOffer("c1")
        h.worker = { c, p -> ScriptedWorker(c, p) { Result.failure(workDataOf(ModelDownloadWorker.KEY_FAILURE to "NO_SPACE")) } }
        m.acceptOffer("c2")
        val next = h.pendingJob().id
        assertNotEquals(failed, next)
        h.runJob()
        val s = h.awaitState(m) { it.phase == DownloadPhase.FAILED && it.failure == DownloadFailure.NO_SPACE }
        assertEquals(DownloadFailure.NO_SPACE, s.failure)
        assertFalse("the new failure is not set aside", next.toString() in h.prefs.current().discardedJobs)
        assertEquals("the old record is gone, not piling up", listOf(next), h.jobs().map { it.id })
    }

    @Test fun theStateCarriesTheDevicesPrefsAndTheModelName() = runBlocking {
        val m = eligibleManager()
        h.prefs.recordNano(ok = false, reason = "no model")
        val s = h.awaitState(m) { it.nanoFailing }
        assertEquals(Eligibility.ELIGIBLE, s.eligibility)
        assertEquals("Test model", s.modelName)
        assertFalse(s.allowMetered)
    }

    @Test fun aDebugSpecFileSwapsInASmallModel() {
        h.device()
        val spec = h.debugSpec(url = "https://models.example/small.bin")
        val m = h.manager()
        assertEquals(spec, m.spec)
        assertEquals(File(LocalReaderManager.storeRoot(h.context), "test.bin"), m.store.model)
    }

    @Test fun aDebugSpecWithoutANameIsCalledDebugModel() {
        h.debugSpec(displayName = null)
        assertEquals("Debug model", h.manager().spec.displayName)
    }

    @Test fun withoutAUsableDebugSpecThePinnedModelIsUsed() {
        assertEquals(ModelSpec.GEMMA_4_E2B, h.manager().spec)
        // A file missing its checksum is ignored rather than trusted.
        File(h.context.noBackupFilesDir, "reader-debug-spec.properties").writeText("url=https://models.example/x\nfileName=x\nsizeBytes=3\n")
        assertEquals(ModelSpec.GEMMA_4_E2B, h.manager().spec)
    }
}
