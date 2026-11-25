package dev.roastbook.app.reader

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The download path the on-device reader depends on: resume, verification, and the failure branches. */
class ModelDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val body: ByteArray = Random(7).nextBytes(9 * 1024 * 1024 + 123)

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun spec(size: Long = body.size.toLong(), hash: String = sha(body)) =
        ModelSpec("Test model", server.url("/model.bin").toString(), "model.bin", size, hash)
    private fun store(spec: ModelSpec) = ModelStore(File(tmp.root, "reader"), spec)

    @Test fun freshDownloadIsVerifiedThenMovedIntoPlace() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val spec = spec(); val store = store(spec)
        val seen = mutableListOf<DownloadProgress>()
        assertEquals(DownloadResult.Done, ModelDownloader(http).download(spec, store) { seen += it })
        assertTrue(store.isReady())
        assertFalse(store.part.exists())
        assertTrue(store.model.readBytes().contentEquals(body))
        assertTrue(seen.any { it.phase == DownloadProgress.Phase.DOWNLOADING && it.bytes == body.size.toLong() })
        assertTrue(seen.any { it.phase == DownloadProgress.Phase.VERIFYING })
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test fun resumeAsksForTheRestAndAppends() = runBlocking {
        val spec = spec(); val store = store(spec)
        store.ensureDirs()
        val have = 3_000_000
        store.part.writeBytes(body.copyOfRange(0, have))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $have-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(body.copyOfRange(have, body.size))),
        )
        assertEquals(DownloadResult.Done, ModelDownloader(http).download(spec, store))
        assertEquals("bytes=$have-", server.takeRequest().getHeader("Range"))
        assertTrue(store.model.readBytes().contentEquals(body))
    }

    @Test fun aServerThatIgnoresTheRangeStartsOver() = runBlocking {
        val spec = spec(); val store = store(spec)
        store.ensureDirs()
        store.part.writeBytes(ByteArray(1000) { 1 }) // not the start of the real file
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        assertEquals(DownloadResult.Done, ModelDownloader(http).download(spec, store))
        assertTrue(store.model.readBytes().contentEquals(body))
    }

    @Test fun aDifferentFileSizeOnTheServerFails() = runBlocking {
        val spec = spec(); val store = store(spec)
        store.ensureDirs()
        store.part.writeBytes(body.copyOfRange(0, 100))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 100-199/999999").setBody("x".repeat(100)))
        val r = ModelDownloader(http).download(spec, store)
        assertEquals(DownloadResult.Reason.SIZE_MISMATCH, (r as DownloadResult.Failed).reason)
        assertFalse(store.part.exists())
    }

    @Test fun aWholeFileOfTheWrongLengthFails() = runBlocking {
        server.enqueue(MockResponse().setBody("too short"))
        val r = ModelDownloader(http).download(spec(), store(spec()))
        assertEquals(DownloadResult.Reason.SIZE_MISMATCH, (r as DownloadResult.Failed).reason)
    }

    @Test fun aChecksumMismatchIsThrownAway() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val spec = spec(hash = "0".repeat(64)); val store = store(spec)
        val r = ModelDownloader(http).download(spec, store)
        assertEquals(DownloadResult.Reason.HASH_MISMATCH, (r as DownloadResult.Failed).reason)
        assertFalse(store.part.exists())
        assertFalse(store.model.exists())
        assertFalse(store.isReady())
    }

    @Test fun cancelingLeavesThePartForALaterResume() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(body)).throttleBody(512 * 1024, 20, TimeUnit.MILLISECONDS))
        val spec = spec(); val store = store(spec)
        lateinit var job: kotlinx.coroutines.Deferred<DownloadResult>
        job = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            ModelDownloader(http).download(spec, store) { p -> if (p.bytes > 0) job.cancel() }
        }
        job.start()
        runCatching { job.await() }
        val kept = store.part.length()
        assertTrue("partial file kept ($kept bytes)", kept in 1 until body.size)
        assertFalse(store.isReady())
    }

    @Test fun serverErrorsAreRetriedLater() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(ModelDownloader(http).download(spec(), store(spec())) is DownloadResult.Retry)
    }

    @Test fun missingFilesFailWithoutRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(DownloadResult.Reason.HTTP, (ModelDownloader(http).download(spec(), store(spec())) as DownloadResult.Failed).reason)
    }

    @Test fun redirectsAreFollowedFromThePublishedUrl() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/cdn/signed?x=1")))
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        assertEquals(DownloadResult.Done, ModelDownloader(http).download(spec(), store(spec())))
        assertEquals("/model.bin", server.takeRequest().path)
        assertEquals("/cdn/signed?x=1", server.takeRequest().path)
    }

    @Test fun aCompletePartIsVerifiedWithoutARequest() = runBlocking {
        val spec = spec(); val store = store(spec)
        store.ensureDirs()
        store.part.writeBytes(body)
        assertEquals(DownloadResult.Done, ModelDownloader(http).download(spec, store))
        assertEquals(0, server.requestCount)
        assertTrue(store.isReady())
    }

    @Test fun aPartLargerThanTheModelIsDiscardedAndTheDownloadStartsOver() = runBlocking {
        val spec = spec(); val store = store(spec)
        store.ensureDirs()
        store.part.writeBytes(ByteArray(body.size + 5))
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        assertEquals(DownloadResult.Done, ModelDownloader(http).download(spec, store))
        assertNull(server.takeRequest().getHeader("Range"))
        assertTrue(store.model.readBytes().contentEquals(body))
    }

    @Test fun tooLittleFreeSpaceFailsBeforeAnyRequest() = runBlocking {
        val spec = spec(size = 1L shl 60)
        val r = ModelDownloader(http).download(spec, store(spec))
        assertEquals(DownloadResult.Reason.NO_SPACE, (r as DownloadResult.Failed).reason)
        assertEquals(0, server.requestCount)
    }

    @Test fun aRangeThatStartsElsewhereIsThrownAwayAndRetried() = runBlocking {
        val spec = spec(); val store = store(spec)
        store.ensureDirs()
        store.part.writeBytes(body.copyOfRange(0, 100))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 50-${body.size - 1}/${body.size}").setBody("x"))
        assertTrue(ModelDownloader(http).download(spec, store) is DownloadResult.Retry)
        assertFalse(store.part.exists())
    }

    @Test fun anUnsatisfiableRangeOnAPartialFileStartsOverLater() = runBlocking {
        val spec = spec(); val store = store(spec)
        store.ensureDirs()
        store.part.writeBytes(body.copyOfRange(0, 100))
        server.enqueue(MockResponse().setResponseCode(416))
        assertTrue(ModelDownloader(http).download(spec, store) is DownloadResult.Retry)
        assertFalse(store.part.exists())
    }

    @Test fun aBodyLongerThanTheModelIsThrownAway() = runBlocking {
        val spec = spec(); val store = store(spec)
        store.ensureDirs()
        store.part.writeBytes(body.copyOfRange(0, 100))
        // The range header is right, but the server sends more than the rest of the file.
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(body.copyOfRange(100, body.size)).write(ByteArray(10))),
        )
        val r = ModelDownloader(http).download(spec, store)
        assertEquals(DownloadResult.Reason.SIZE_MISMATCH, (r as DownloadResult.Failed).reason)
        assertFalse(store.part.exists())
    }

    @Test fun aConnectionDroppedMidFileKeepsWhatArrivedAndRetries() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(body)).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val spec = spec(); val store = store(spec)
        val r = ModelDownloader(http).download(spec, store)
        assertTrue(r.toString(), r is DownloadResult.Retry)
        val kept = store.part.length()
        assertTrue("what arrived is kept for the resume ($kept bytes)", kept in 1 until body.size)
        assertTrue("and it is the start of the file", store.part.readBytes().contentEquals(body.copyOfRange(0, kept.toInt())))
        assertFalse(store.isReady())
    }

    @Test fun anUnreachableServerIsRetriedLater() = runBlocking {
        val spec = spec()
        server.shutdown()
        assertTrue(ModelDownloader(http).download(spec, store(spec)) is DownloadResult.Retry)
    }

    @Test fun aModelThatCannotBeMovedIntoPlaceIsAnError() {
        val spec = spec(); val store = store(spec)
        val missing = File(tmp.root, "gone.part")
        val e = runCatching { store.promote(missing, spec.sha256) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertFalse(store.isReady())
    }

    @Test fun aReadyModelIsNotDownloadedAgainAndDeleteRemovesEverything() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val spec = spec(); val store = store(spec)
        ModelDownloader(http).download(spec, store)
        File(store.cacheDir, "compiled.bin").writeBytes(ByteArray(10))
        assertEquals(DownloadResult.Done, ModelDownloader(http).download(spec, store))
        assertEquals(1, server.requestCount)
        assertEquals(body.size + 10L + 64L, store.sizeOnDisk())
        store.deleteAll()
        assertFalse(store.isReady())
        assertEquals(0L, store.sizeOnDisk())
    }
}
