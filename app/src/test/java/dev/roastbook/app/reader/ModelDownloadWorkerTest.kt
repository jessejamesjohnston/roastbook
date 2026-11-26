package dev.roastbook.app.reader

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker.Result
import androidx.work.ProgressUpdater
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import dev.roastbook.app.R
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The download job: what it returns to WorkManager, the progress it reports, and its notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ModelDownloadWorkerTest {
    private val h = ReaderHarness()
    private val context: Context get() = h.context
    private lateinit var server: MockWebServer
    private val body: ByteArray = Random(11).nextBytes(5 * 1024 * 1024 + 17)

    private val progress = mutableListOf<Data>()
    private val shown = mutableListOf<ForegroundInfo>()
    /** What the system answers when the job asks to become a foreground service. */
    private var foregroundAnswer: () -> ListenableFuture<Void?> = { Done() }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        h.device()
    }

    @After fun tearDown() {
        server.shutdown()
        h.close()
    }

    private fun manager(bytes: ByteArray = body): LocalReaderManager {
        h.debugSpec(bytes = bytes, url = server.url("/model.bin").toString())
        return h.manager()
    }

    private fun worker(m: LocalReaderManager, attempt: Int = 0): ModelDownloadWorker =
        TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    ModelDownloadWorker(appContext, workerParameters, m)
            })
            .setProgressUpdater(object : ProgressUpdater {
                override fun updateProgress(context: Context, id: UUID, data: Data): ListenableFuture<Void?> { synchronized(progress) { progress += data }; return Done() }
            })
            .setForegroundUpdater(object : ForegroundUpdater {
                override fun setForegroundAsync(context: Context, id: UUID, info: ForegroundInfo): ListenableFuture<Void?> {
                    synchronized(shown) { shown += info }
                    return foregroundAnswer()
                }
            })
            .build()

    private fun Notification.title() = extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString()
    private fun Notification.text() = extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()

    @Test fun aFinishedDownloadSucceedsAndLeavesAReadyModel() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val m = manager()
        assertEquals(Result.success(), worker(m).doWork())
        assertTrue(m.isReady())
        // Progress for Settings: bytes as they arrive, then the check.
        val phases = progress.map { it.getString(ModelDownloadWorker.KEY_PHASE) }
        assertTrue(phases.contains("DOWNLOADING"))
        assertTrue(phases.contains("VERIFYING"))
        val last = progress.last { it.getString(ModelDownloadWorker.KEY_PHASE) == "DOWNLOADING" }
        assertEquals(body.size.toLong(), last.getLong(ModelDownloadWorker.KEY_BYTES, -1))
        assertEquals(body.size.toLong(), last.getLong(ModelDownloadWorker.KEY_TOTAL, -1))
    }

    @Test fun theNotificationShowsProgressThenTheCheckWithAPauseAction() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val w = worker(manager())
        w.doWork()
        val first = shown.first()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, first.foregroundServiceType)
        assertEquals(context.getString(R.string.reader_notif_downloading), first.notification.title())
        assertEquals(context.getString(R.string.reader_notif_pause), first.notification.actions.single().title.toString())
        // Pause cancels this job, not some other work id.
        val cancelThisJob = androidx.work.WorkManager.getInstance(context).createCancelPendingIntent(w.id)
        assertEquals(cancelThisJob, first.notification.actions.single().actionIntent)
        assertEquals(cancelThisJob, shown.last().notification.actions.single().actionIntent)
        assertTrue(first.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val sizes = dev.roastbook.app.util.English.fileSize(body.size.toLong())
        assertTrue(shown.any { it.notification.text() == context.getString(R.string.reader_notif_progress, sizes, sizes) })
        assertEquals(context.getString(R.string.reader_notif_verifying), shown.last().notification.title())
        // Re-posted on whole-percent steps, not for every chunk.
        assertTrue("posted ${shown.size} times", shown.size <= 103)
        val nm = context.getSystemService(NotificationManager::class.java)
        assertEquals(context.getString(R.string.reader_notif_channel), nm.getNotificationChannel("reader-download").name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel("reader-download").importance)
    }

    @Test fun theForegroundInfoBeforeStartingNamesTheWholeModel() = runBlocking {
        val m = manager()
        val info = worker(m).getForegroundInfo()
        assertEquals(context.getString(R.string.reader_notif_downloading), info.notification.title())
        val zero = dev.roastbook.app.util.English.fileSize(0)
        val total = dev.roastbook.app.util.English.fileSize(body.size.toLong())
        assertEquals(context.getString(R.string.reader_notif_progress, zero, total), info.notification.text())
    }

    @Test fun aRefusedForegroundServiceStillDownloadsAsAPlainJob() = runBlocking {
        foregroundAnswer = { Done(error = IllegalStateException("app in background")) }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val m = manager()
        // Run twice: the second run finds the channel already there.
        assertEquals(Result.success(), worker(m).doWork())
        assertTrue(m.isReady())
        assertEquals(Result.success(), worker(m).doWork())
    }

    @Test fun cancelingWhileAskingForTheForegroundStopsTheJob() = runBlocking {
        foregroundAnswer = { Done(canceled = true) }
        val m = manager()
        try {
            worker(m).doWork()
            fail("expected the job to stop")
        } catch (_: CancellationException) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun aNetworkFailureAsksForARetryUntilTheLimit() = runBlocking {
        val m = manager()
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(Result.retry(), worker(m, attempt = 0).doWork())
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(Result.retry(), worker(m, attempt = 7).doWork())
        // The eighth retry gives up and says the network was the cause.
        server.enqueue(MockResponse().setResponseCode(503))
        val r = worker(m, attempt = 8).doWork()
        assertTrue(r is Result.Failure)
        assertEquals("NETWORK", r.outputData.getString(ModelDownloadWorker.KEY_FAILURE))
    }

    @Test fun aPermanentFailureNamesItsReason() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val r = worker(manager()).doWork()
        assertTrue(r is Result.Failure)
        assertEquals("HTTP", r.outputData.getString(ModelDownloadWorker.KEY_FAILURE))
    }

    @Test fun aChecksumFailureNamesItsReasonAndRefreshesTheSizeOnDisk() = runBlocking {
        val m = manager()
        // A resumed download whose rest has one wrong byte: the digest won't match.
        val have = 1_000
        m.store.ensureDirs()
        m.store.part.writeBytes(body.copyOfRange(0, have))
        val rest = body.copyOfRange(have, body.size).also { it[0] = (it[0] + 1).toByte() }
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $have-${body.size - 1}/${body.size}").setBody(Buffer().write(rest)))
        // Settings is open the whole time: the screen that showed the partial must see it go.
        val sawPart = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sawItGo = kotlinx.coroutines.CompletableDeferred<Unit>()
        val settings = launch(Dispatchers.IO) {
            m.state.collect { s ->
                if (s.sizeOnDisk > 0) sawPart.complete(Unit)
                else if (sawPart.isCompleted) sawItGo.complete(Unit)
            }
        }
        try {
            kotlinx.coroutines.withTimeout(10_000) { sawPart.await() }
            val r = worker(m).doWork()
            assertEquals("HASH_MISMATCH", r.outputData.getString(ModelDownloadWorker.KEY_FAILURE))
            assertFalse(m.store.part.exists())
            kotlinx.coroutines.withTimeout(10_000) { sawItGo.await() }
        } finally {
            settings.cancel()
        }
    }

    @Test fun cancelingTheJobMidDownloadKeepsThePartForAResume() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(body)).throttleBody(256 * 1024, 20, TimeUnit.MILLISECONDS))
        val m = manager()
        val w = worker(m)
        lateinit var job: kotlinx.coroutines.Deferred<Result>
        job = async(Dispatchers.IO, start = CoroutineStart.LAZY) { w.doWork() }
        job.start()
        // Wait for the first bytes, then stop it as Pause does.
        val deadline = System.currentTimeMillis() + 10_000
        while (synchronized(progress) { progress.none { it.getLong(ModelDownloadWorker.KEY_BYTES, 0) > 0 } } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        job.cancel()
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        val kept = m.store.part.length()
        assertTrue("partial file kept ($kept bytes)", kept in 1 until body.size)
        assertFalse(m.isReady())
    }

    /** An already-settled future: done, failed with [error], or canceled. */
    private class Done(private val error: Throwable? = null, private val canceled: Boolean = false) : ListenableFuture<Void?> {
        override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = canceled
        override fun isDone() = true
        override fun get(): Void? {
            if (canceled) throw CancellationException("canceled")
            if (error != null) throw ExecutionException(error)
            return null
        }
        override fun get(timeout: Long, unit: TimeUnit): Void? = get()
    }
}
