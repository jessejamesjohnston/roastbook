package dev.roastbook.app.extraction

import android.graphics.Bitmap
import dev.roastbook.app.cloud.CloudTier
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.testutil.TestGraph
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The read's remaining exits: a tier that hangs or runs out of memory falls through to the next one,
 * and a read that is canceled (the app left, the job stopped) is never written down as a failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExtractorBranchesTest {
    private lateinit var g: TestGraph
    private val clock = Clock { 1_000_000L }

    @Before fun setUp() { g = TestGraph() }
    @After fun tearDown() { g.close() }

    private fun extractor(
        nano: NanoTier = ScriptedNano(status = NanoStatus.UNAVAILABLE),
        cloud: CloudTier = OffCloud,
        local: LocalTier = ScriptedReader(),
        photos: PhotoStore = g.photos,
    ) = Extractor(g.db, photos, nano, TextOcr(), cloud, local, MemoryHealth(), clock, shortTimeouts)

    private suspend fun trail(id: String) = g.dao.get(id)!!.extractionTrail.orEmpty()

    /** Runs the read and expects it to end by cancellation, leaving the row as it was. */
    private suspend fun assertCanceledWithoutARecord(id: String, run: suspend () -> Unit) {
        try {
            run()
            fail("the cancellation must reach the caller")
        } catch (e: CancellationException) {
            val c = g.dao.get(id)!!
            assertEquals(CoffeeState.EXTRACTING, c.state)
            // The first-download badge may be showing (the download carries on); nothing reads as failed.
            assertTrue("no tier was written down as failed: ${c.extractionTrail}", c.extractionTrail.orEmpty().let { "failed" !in it && "guess" !in it })
        }
    }

    // --- the on-device reader ---

    @Test fun aReaderThatNeverAnswersIsGivenUpAndTheReadGoesOn() = runBlocking {
        g.extractingCoffee("c1")
        val reader = ScriptedReader(LocalAvailability.READY) { awaitCancellation() }
        extractor(local = reader).run("c1")
        assertEquals(CoffeeState.LOGGED, g.dao.get("c1")!!.state)
        assertTrue(trail("c1"), trail("c1").contains("local=failed"))
    }

    @Test fun aReaderThatRunsOutOfMemoryFallsThrough() = runBlocking {
        g.extractingCoffee("c1")
        val reader = ScriptedReader(LocalAvailability.READY) { throw OutOfMemoryError("model too large") }
        val out = extractor(local = reader).run("c1")
        assertTrue(out.note, out.note.contains("out of memory"))
        assertTrue(trail("c1").contains("local=failed"))
    }

    @Test fun aCanceledReaderCallIsNotAFailure() = runBlocking {
        g.extractingCoffee("c1")
        val reader = ScriptedReader(LocalAvailability.READY) { throw CancellationException("the app left") }
        assertCanceledWithoutARecord("c1") { extractor(local = reader).run("c1") }
    }

    // --- the cloud key ---

    @Test fun aCloudCallThatNeverAnswersFallsBackToTheGuess() = runBlocking {
        g.extractingCoffee("c1")
        val out = extractor(cloud = ScriptedCloud { awaitCancellation() }).run("c1")
        assertTrue(out.note, out.note.contains("cloud timed out"))
        assertTrue(trail("c1").contains("cloud=failed"))
    }

    @Test fun whyTheCloudCallFailedIsKeptForTheCard() = runBlocking {
        // Photos that decode, so the label text is read and the coffee ends as a label-text read.
        g.close(); g = TestGraph(loadPhotos = true)
        val cases = listOf<Pair<Throwable?, Int>>(
            dev.roastbook.app.cloud.GeminiRequestException(400, "bad key", reason = "API_KEY_INVALID") to dev.roastbook.app.R.string.what_ocr_cloud_key,
            dev.roastbook.app.cloud.GeminiRequestException(403, "denied") to dev.roastbook.app.R.string.what_ocr_cloud_key,
            dev.roastbook.app.cloud.GeminiRequestException(404, "no such model") to dev.roastbook.app.R.string.what_ocr_cloud_model_named,
            dev.roastbook.app.cloud.GeminiRequestException(429, "quota") to dev.roastbook.app.R.string.what_ocr_cloud_limit,
            dev.roastbook.app.cloud.UnusableKeyException() to dev.roastbook.app.R.string.what_ocr_cloud_unusable,
            IOException("offline") to dev.roastbook.app.R.string.what_ocr_cloud_network,
            dev.roastbook.app.cloud.GeminiRequestException(500, "server") to dev.roastbook.app.R.string.what_ocr_cloud_failed,
            // null: a call that never answers: Google may well have been reached, so not "offline".
            null to dev.roastbook.app.R.string.what_ocr_cloud_failed,
        )
        cases.forEachIndexed { i, (thrown, line) ->
            val id = "c$i"
            g.extractingCoffee(id)
            val out = Extractor(g.db, g.photos, ScriptedNano(status = NanoStatus.UNAVAILABLE), TextOcr("NORTHWIND"), ScriptedCloud { thrown?.let { throw it } ?: awaitCancellation() }, ScriptedReader(), MemoryHealth(), clock, shortTimeouts).run(id)
            assertEquals(dev.roastbook.app.data.db.ExtractionSource.OCR, out.source)
            val shown = WhatHappenedRules.explain(dev.roastbook.app.data.db.ExtractionSource.OCR, ExtractionTrail.decode(trail(id)), hasOcrText = true, readerOffered = false, hasKey = true)!!
            assertEquals("case $i: ${trail(id)}", line, shown.line)
            // Only the unknown model is named: the one the read tried.
            assertEquals("case $i", if (line == dev.roastbook.app.R.string.what_ocr_cloud_model_named) "scripted-cloud" else null, shown.lineArg)
        }
    }

    @Test fun aCanceledCloudCallIsNotAFailure() = runBlocking {
        g.extractingCoffee("c1")
        assertCanceledWithoutARecord("c1") { extractor(cloud = ScriptedCloud { throw CancellationException("key removed") }).run("c1") }
    }

    // --- Gemini Nano ---

    @Test fun aStatusCheckThatThrowsCountsAsNanoUnavailable() = runBlocking {
        g.extractingCoffee("c1")
        val nano = ScriptedNano(onStatus = { throw IllegalStateException("AICore not bound") })
        extractor(nano = nano).run("c1")
        assertTrue(trail("c1"), trail("c1").startsWith("nano=unsupported"))
        assertEquals(0, nano.calls)
    }

    @Test fun aCanceledStatusCheckIsNotAFailure() = runBlocking {
        g.extractingCoffee("c1")
        val nano = ScriptedNano(onStatus = { throw CancellationException("the app left") })
        assertCanceledWithoutARecord("c1") { extractor(nano = nano).run("c1") }
    }

    @Test fun aCanceledModelDownloadIsNotAFailure() = runBlocking {
        g.extractingCoffee("c1")
        val nano = ScriptedNano(status = NanoStatus.DOWNLOADABLE, onPrepare = { throw CancellationException("the app left") })
        assertCanceledWithoutARecord("c1") { extractor(nano = nano).run("c1") }
    }

    @Test fun aCanceledNanoCallIsNotAFailure() = runBlocking {
        g.extractingCoffee("c1")
        val nano = ScriptedNano(answers = mutableListOf({ throw CancellationException("the app left") }))
        assertCanceledWithoutARecord("c1") { extractor(nano = nano).run("c1") }
    }

    // --- the shared read pass ---

    private fun photosThatThrow(e: Throwable) = object : PhotoStore(g.context) {
        override suspend fun load(relative: String, maxEdge: Int): Bitmap? = throw e
    }

    @Test fun aReadThatThrowsIsRecordedWithARetryTime() = runBlocking {
        g.extractingCoffee("c1") { copy(ocrText = "earlier text") }
        val pass = ReadPass(g.dao, extractor(photos = photosThatThrow(IOException("storage unavailable"))))
        assertNull(pass.readIfDue("c1", RunMode.FOREGROUND))
        val c = g.dao.get("c1")!!
        assertEquals(1, c.extractionAttempts)
        assertTrue("the scheduler has a time to wake for", (c.nextAttemptAt ?: 0) > clock.now())
        assertTrue(c.extractionMeta.orEmpty(), c.extractionMeta.orEmpty().contains("IOException"))
    }

    @Test fun aReadPassThatIsCanceledRecordsNothing() = runBlocking {
        g.extractingCoffee("c1")
        val pass = ReadPass(g.dao, extractor(photos = photosThatThrow(CancellationException("the app left"))))
        try {
            pass.readIfDue("c1", RunMode.BACKGROUND)
            fail("the cancellation must reach the caller")
        } catch (e: CancellationException) {
            assertEquals(0, g.dao.get("c1")!!.extractionAttempts)
        }
    }
}
