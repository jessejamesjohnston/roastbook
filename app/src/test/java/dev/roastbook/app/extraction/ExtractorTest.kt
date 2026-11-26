package dev.roastbook.app.extraction

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.reminders.ReminderScheduler
import dev.roastbook.app.vision.CropResult
import dev.roastbook.app.vision.HeroProcessor
import dev.roastbook.app.vision.PaletteExtractor
import dev.roastbook.app.vision.SubjectCropper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every branch of the state machine, with fake tiers. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one runs the orphan-photo sweep at startup and would delete fixtures.
@Config(sdk = [35], application = android.app.Application::class)
class ExtractorTest {
    private lateinit var db: RoastbookDatabase
    private lateinit var photos: PhotoStore
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The real repository, for the Read-again flip and the edit path that records the per-row marker. */
    private val repo: CoffeeRepository by lazy {
        val cropper = object : SubjectCropper {
            override suspend fun crop(bitmap: Bitmap) = CropResult(android.graphics.Rect(0, 0, bitmap.width, bitmap.height), null, false)
        }
        val palette = object : PaletteExtractor { override fun extract(subject: Bitmap) = dev.roastbook.app.color.RowColors(0, 0, 0, 0, fallback = true) }
        val noExport = object : dev.roastbook.app.data.photo.SquareExporter { override suspend fun maybeExport(square: File, displayName: String) {} }
        val userPrefs = dev.roastbook.app.data.prefs.UserPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(context.filesDir, "test-${java.util.UUID.randomUUID()}.preferences_pb") })
        CoffeeRepository(context, db.coffeeDao(), photos, HeroProcessor(db.coffeeDao(), photos, cropper, palette, noExport), ReminderScheduler(context, db.coffeeDao()), userPrefs)
    }

    private class FakeNano(
        var status: NanoStatus = NanoStatus.AVAILABLE,
        val answers: MutableList<suspend () -> String> = mutableListOf(),
        val prepareFails: Boolean = false,
        var onPrepare: suspend () -> Unit = {},
        var onStatus: suspend () -> Unit = {},
    ) : NanoTier {
        var calls = 0
        var prepares = 0
        override suspend fun status(): NanoStatus { onStatus(); return status }
        override suspend fun prepare() { prepares++; onPrepare(); if (prepareFails) throw IllegalStateException("download failed") }
        override suspend fun generate(images: List<Bitmap>, prompt: String): String { calls++; return answers.removeAt(0)() }
        override fun modelName() = "fake-nano"
    }
    private object NoCloud : dev.roastbook.app.cloud.CloudTier {
        override suspend fun available() = false
        override suspend fun generate(images: List<Bitmap>, prompt: String) = error("unused")
        override suspend fun modelName() = "none"
    }
    private class FakeCloud(val answers: MutableList<String>) : dev.roastbook.app.cloud.CloudTier {
        var calls = 0
        override suspend fun available() = true
        override suspend fun generate(images: List<Bitmap>, prompt: String): String { calls++; return answers.removeAt(0) }
        override suspend fun modelName() = "fake-cloud"
    }
    private class FakeOcr(val text: String) : OcrTier { override suspend fun recognize(image: Bitmap) = text }
    private object NoLocal : LocalTier {
        override suspend fun availability() = LocalAvailability.UNSUPPORTED
        override suspend fun generate(images: List<Bitmap>, prompt: String) = error("unused")
        override fun modelName() = "none"
    }
    private class FakeLocal(
        var availability: LocalAvailability = LocalAvailability.READY,
        val answers: MutableList<suspend () -> String> = mutableListOf(),
    ) : LocalTier {
        var calls = 0
        override suspend fun availability() = availability
        override suspend fun generate(images: List<Bitmap>, prompt: String): String { calls++; return answers.removeAt(0)() }
        override fun modelName() = "fake-local"
    }
    private class FakeHealth : NanoHealth {
        var failing = false
        val records = mutableListOf<Boolean>()
        override suspend fun nanoFailing() = failing
        override suspend fun recordNano(ok: Boolean, reason: String?) { records += ok; failing = !ok }
        var preferred = false
        override suspend fun readerPreferred() = preferred
        val waitingForReader = mutableListOf<String>()
        override suspend fun waitForReaderIfComing(coffeeId: String) { waitingForReader += coffeeId }
    }
    private var now = 1_000_000L
    private val clock = Clock { now }
    private val health = FakeHealth()

    private fun extractor(nano: NanoTier, ocr: OcrTier, cloud: dev.roastbook.app.cloud.CloudTier = NoCloud, local: LocalTier = NoLocal) =
        Extractor(db, photos, nano, ocr, cloud, local, health, clock, timeouts)
    /** Real time (runBlocking), short bounds: a fake that hangs costs a second or two, not minutes. */
    private val timeouts = ExtractionTimeouts(statusMs = 1_000, preparingBadgeMs = 0, prepareMs = 1_000, nanoMs = 2_000, localMs = 2_000, cloudMs = 2_000)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, RoastbookDatabase::class.java).allowMainThreadQueries().build()
        // Image decoding is not what these tests are about: hand the extractor a stub bitmap.
        photos = object : PhotoStore(context) {
            override suspend fun load(relative: String, maxEdge: Int): Bitmap? =
                if (file(relative).exists()) Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) else null
        }
    }
    @After fun tearDown() { db.close() }

    private suspend fun seedCoffee(withPhoto: Boolean = true): String {
        val id = "c1"
        db.coffeeDao().insert(CoffeeEntity(id, 1, 1, 1, CoffeeState.EXTRACTING))
        if (withPhoto) {
            val f = File(photos.dir(id), "p1.jpg")
            f.writeBytes(byteArrayOf(1, 2, 3))
            db.coffeeDao().insertPhotos(listOf(CoffeePhotoEntity("p1", id, PhotoRole.FRONT, 0, "photos/$id/p1.jpg", takenAt = 1, addedAt = 1)))
        }
        return id
    }

    // --- A read never loops on a bag that takes the process down, and never stalls the queue ---

    @Test fun aLocalReadIsCountedBeforeTheModelRuns() = runBlocking {
        val id = seedCoffee()
        var attemptsSeenByTheModel = -1
        val local = FakeLocal(answers = mutableListOf({ attemptsSeenByTheModel = db.coffeeDao().get(id)!!.extractionAttempts; """{"roaster":"Larkmoor"}""" }))
        val out = extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("LARKMOOR"), NoCloud, local).run(id)
        assertEquals(ExtractionSource.LOCAL, out.source)
        assertEquals("a read that ends the process is already counted", 1, attemptsSeenByTheModel)
        assertEquals("a read that returns is counted once", 1, db.coffeeDao().get(id)!!.extractionAttempts)
    }

    @Test fun anUnexpectedFailureIsGivenARetryTime() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(), FakeOcr("")).recordFailure(id, null, "error: boom")
        val c = db.coffeeDao().get(id)!!
        assertEquals(1, c.extractionAttempts)
        org.junit.Assert.assertTrue("the scheduler has a time to wake for", (c.nextAttemptAt ?: 0) > now)
    }

    @Test fun everyModelSeesAtMostFourPhotosWhileTheLabelTextCoversAll() = runBlocking {
        // Photos can be added without limit; each model tier gets the first four, decoded once.
        val decodes = mutableListOf<Int>()
        photos = object : PhotoStore(context) {
            override suspend fun load(relative: String, maxEdge: Int): Bitmap? {
                synchronized(decodes) { decodes += maxEdge }
                return Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            }
        }
        val id = "c6"
        db.coffeeDao().insert(CoffeeEntity(id, 1, 1, 1, CoffeeState.EXTRACTING))
        db.coffeeDao().insertPhotos((0 until 6).map { CoffeePhotoEntity("p$it", id, if (it == 0) PhotoRole.FRONT else PhotoRole.OTHER, it, "photos/$id/p$it.jpg", takenAt = 1, addedAt = 1) })
        val sent = mutableMapOf<String, Int>()
        val nano = object : NanoTier {
            override suspend fun status() = NanoStatus.AVAILABLE
            override suspend fun prepare() {}
            override suspend fun generate(images: List<Bitmap>, prompt: String): String { sent["nano"] = images.size; return "{}" }
            override fun modelName() = "nano"
        }
        val local = object : LocalTier {
            override suspend fun availability() = LocalAvailability.READY
            override suspend fun generate(images: List<Bitmap>, prompt: String): String { sent["local"] = images.size; return "{}" }
            override fun modelName() = "local"
        }
        val cloud = object : dev.roastbook.app.cloud.CloudTier {
            override suspend fun available() = true
            override suspend fun generate(images: List<Bitmap>, prompt: String): String { sent["cloud"] = images.size; return """{"roaster":"Larkmoor"}""" }
            override suspend fun modelName() = "cloud"
        }
        extractor(nano, FakeOcr("text"), cloud, local).run(id)
        assertEquals(ExtractionSource.CLOUD, db.coffeeDao().get(id)!!.extractionSource)
        assertEquals(mapOf("nano" to 4, "local" to 4, "cloud" to 4), sent)
        assertEquals("every photo is read for its text", 6, decodes.count { it == 2048 })
        assertEquals("the model images are decoded once", 4, decodes.count { it == 1024 })
    }

    @Test fun aRecognitionThatNeverCompletesDoesNotHoldTheRead() = runBlocking {
        val id = seedCoffee()
        val hanging = object : OcrTier { override suspend fun recognize(image: Bitmap): String = kotlinx.coroutines.suspendCancellableCoroutine<String> { } }
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"Larkmoor"}""" }))
        val out = Extractor(db, photos, nano, hanging, NoCloud, NoLocal, health, clock, timeouts.copy(ocrMs = 300)).run(id)
        assertEquals(ExtractionSource.NANO, out.source)
    }

    @Test fun nanoHappyPath() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"NORTHWIND COFFEE ROASTERS","coffee_name":"Finca Veleta","elevation_m":1520}""" }))
        val out = extractor(nano, FakeOcr("NORTHWIND"), NoCloud).run(id)
        assertEquals(ExtractionSource.NANO, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.LOGGED, c.state)
        assertEquals("Northwind Coffee Roasters", c.roaster)
        assertEquals(1520, c.elevationM)
        assertEquals(ExtractionSource.NANO, c.extractionSource)
        assertEquals(1, nano.calls)
    }

    @Test fun placeholderWordsAndResidueFromAModelAnswerAreNeverSaved() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({
            "\"roaster\": \"Lantern Roasters\",\n\"coffee_name\": \"Finca Veleta\",\",\n\"roast_date\": null null\n\"process\": \"null null\""
        }))
        extractor(nano, FakeOcr("LANTERN"), NoCloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Lantern Roasters", c.roaster)
        assertEquals("Finca Veleta", c.coffeeName)
        assertNull(c.roastDate)
        assertNull(c.process)
    }

    @Test fun theCloudTierPassesTheSameCleanup() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(status = NanoStatus.UNAVAILABLE)
        val cloud = FakeCloud(mutableListOf("""{"roaster":"Signal Roasters Co.","coffee_name":"N/A","roast_date":"roasted fresh","region":"[Huila"}"""))
        extractor(nano, FakeOcr("SIGNAL"), cloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Signal Roasters Co.", c.roaster)
        assertNull(c.coffeeName)
        assertNull(c.roastDate)
        assertEquals("Huila", c.region)
    }

    @Test fun invalidJsonRetriesOnceThenSucceeds() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ "I can't help with that." }, { """{"roaster":"Larkmoor Coffee"}""" }))
        val out = extractor(nano, FakeOcr("x"), NoCloud).run(id)
        assertEquals(ExtractionSource.NANO, out.source)
        assertEquals(2, nano.calls)
        assertEquals("Larkmoor Coffee", db.coffeeDao().get(id)!!.roaster)
    }

    @Test fun invalidJsonTwiceFallsBackToOcr() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ "nope" }, { "still nope" }))
        val out = extractor(nano, FakeOcr("LARKMOOR COFFEE"), NoCloud).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.LOGGED, c.state)
        // The rule-based OCR guess: the brand line on the front is guessed
        // as the roaster instead of leaving every field empty.
        assertEquals("Larkmoor Coffee", c.roaster)
        assertEquals("nano=failed;local=unsupported;cloud=off;guess=1", c.extractionTrail)
        assertEquals("--- photo 1 (front) ---\nLARKMOOR COFFEE", c.ocrText)
    }

    @Test fun nanoUnavailableUsesOcr() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(status = NanoStatus.UNAVAILABLE)
        val out = extractor(nano, FakeOcr("text"), NoCloud).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        assertEquals(0, nano.calls)
    }

    @Test fun nanoThrowsUsesOcr() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ throw IllegalStateException("boom") }))
        val out = extractor(nano, FakeOcr("text"), NoCloud).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        assertEquals(CoffeeState.LOGGED, db.coffeeDao().get(id)!!.state)
    }

    @Test fun transientFailureKeepsExtractingAndCountsAttempt() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ throw TransientExtractionException("background blocked") }))
        val out = extractor(nano, FakeOcr("text"), NoCloud).run(id)
        assertEquals(ExtractionSource.NONE, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals(1, c.extractionAttempts)
    }

    // --- The built-in AI's usage limit: AICore refuses an app's reads for a while after a run of them ---

    /** What AICore throws once an app has read several bags back to back (ML Kit error code 9, BUSY). */
    private fun quotaRefusal(retry: java.time.Duration? = null) =
        if (retry == null) com.google.mlkit.genai.common.GenAiException("out of usage quota", null, 9)
        else com.google.mlkit.genai.common.GenAiException("out of usage quota", null, 9, retry)

    @Test fun aUsageLimitRefusalWaitsAMinuteAndTriesAgainWithoutCallingTheBuiltInAiBroken() = runBlocking {
        val id = seedCoffee()
        val out = extractor(FakeNano(answers = mutableListOf({ throw quotaRefusal() })), FakeOcr("text")).run(id)
        assertEquals(ExtractionSource.NONE, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals(1, c.extractionAttempts)
        assertEquals(now + 60_000L, c.nextAttemptAt)
        assertEquals(TierOutcome.BUSY, ExtractionTrail.decode(c.extractionTrail)[Tier.NANO])
        assertFalse("a usage limit says nothing about the device", health.failing)
    }

    @Test fun aUsageLimitThatNamesALongerWaitIsWaitedOut() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(answers = mutableListOf({ throw quotaRefusal(java.time.Duration.ofSeconds(150)) })), FakeOcr("text")).run(id)
        assertEquals(now + 150_000L, db.coffeeDao().get(id)!!.nextAttemptAt)
    }

    /** A wait longer than a few minutes is not waited out: the bag reads what it can now and says why. */
    @Test fun aUsageLimitThatNamesAnHoursLongWaitReadsWhatItCanNow() = runBlocking {
        val id = seedCoffee()
        val out = extractor(FakeNano(answers = mutableListOf({ throw quotaRefusal(java.time.Duration.ofHours(2)) })), FakeOcr("ROAST DATE 03NOV26")).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.LOGGED, c.state)
        val trail = ExtractionTrail.decode(c.extractionTrail)
        assertEquals(TierOutcome.NOT_READY, trail[Tier.NANO])
        assertEquals(NanoLimits.Kind.READS, trail.nanoLimit)
        assertFalse(health.failing)
    }

    /** The battery limit is recorded as such, so the card can say what really happened. */
    @Test fun theBatteryLimitIsRecordedAsTheBatteryLimit() = runBlocking {
        val id = seedCoffee()
        val battery = com.google.mlkit.genai.common.GenAiException("battery", null, 27)
        extractor(FakeNano(answers = mutableListOf({ throw battery })), FakeOcr("text")).run(id)
        val trail = ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail)
        assertEquals(TierOutcome.BUSY, trail[Tier.NANO])
        assertEquals(NanoLimits.Kind.BATTERY, trail.nanoLimit)
    }

    @Test fun aSecondRefusalWaitsLongerAndTheThirdReadsWhatItCanWithoutTheBuiltInAi() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(answers = mutableListOf({ throw quotaRefusal() })), FakeOcr("text")).run(id)
        now += 60_000L
        extractor(FakeNano(answers = mutableListOf({ throw quotaRefusal() })), FakeOcr("text")).run(id)
        assertEquals(now + 120_000L, db.coffeeDao().get(id)!!.nextAttemptAt)
        now += 120_000L
        val out = extractor(FakeNano(answers = mutableListOf({ throw quotaRefusal() })), FakeOcr("ROAST DATE 03NOV26")).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.LOGGED, c.state)
        assertEquals(TierOutcome.NOT_READY, ExtractionTrail.decode(c.extractionTrail)[Tier.NANO])
        assertEquals(NanoLimits.Kind.READS, ExtractionTrail.decode(c.extractionTrail).nanoLimit)
        assertFalse(health.failing)
    }

    @Test fun nothingReadableEndsAsNone() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(status = NanoStatus.UNAVAILABLE)
        val out = extractor(nano, FakeOcr(""), NoCloud).run(id)
        assertEquals(ExtractionSource.NONE, out.source)
        assertEquals(CoffeeState.LOGGED, db.coffeeDao().get(id)!!.state)
    }

    @Test fun emptyFactsFromNanoFallBackToOcr() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ "{}" }, { "{}" }))
        val out = extractor(nano, FakeOcr("something"), NoCloud).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
    }

    @Test fun reReadKeepsARatedCoffeeRated() = runBlocking {
        val id = seedCoffee()
        db.coffeeDao().get(id)!!.let { db.coffeeDao().update(it.copy(overall = 4.2f, aroma = 4, flavor = 4, finish = 5)) }
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"X"}""" }))
        extractor(nano, FakeOcr("x"), NoCloud).run(id)
        assertEquals(CoffeeState.RATED, db.coffeeDao().get(id)!!.state)
    }

    // --- Concurrency and the attempt cap ---

    @Test fun ratingAndReminderWrittenDuringTheRunSurvive() = runBlocking {
        val id = seedCoffee()
        val dao = db.coffeeDao()
        val nano = FakeNano(answers = mutableListOf({
            // While Nano "thinks", the user answers the tried sheet and rates the coffee.
            dao.setRemindAt(id, 123L, 1)
            dao.get(id)!!.let { dao.update(it.copy(aroma = 4, flavor = 4, finish = 5, overall = 4.33f, notes = "lovely")) }
            """{"roaster":"X", "coffee_name":"Y"}"""
        }))
        extractor(nano, FakeOcr("x"), NoCloud).run(id)
        val c = dao.get(id)!!
        assertEquals("X", c.roaster)
        assertEquals(4.33f, c.overall)
        assertEquals("lovely", c.notes)
        assertEquals(123L, c.remindAt)
        assertEquals(CoffeeState.RATED, c.state)
        assertEquals(ExtractionSource.NANO, c.extractionSource)
    }

    @Test fun thirdTransientFailureGivesUpWithOcr() = runBlocking {
        val id = seedCoffee()
        db.coffeeDao().get(id)!!.let { db.coffeeDao().update(it.copy(extractionAttempts = 2)) }
        val nano = FakeNano(answers = mutableListOf({ throw TransientExtractionException("background blocked") }))
        val out = extractor(nano, FakeOcr("text"), NoCloud).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.LOGGED, c.state)
        assertEquals(ExtractionSource.OCR, c.extractionSource)
        assert(c.extractionMeta!!.contains("gave up after 3 attempts")) { c.extractionMeta }
    }

    @Test fun schedulerErrorAtTheCapFinishesTheRow() = runBlocking {
        val id = seedCoffee()
        db.coffeeDao().get(id)!!.let { db.coffeeDao().update(it.copy(extractionAttempts = 2)) }
        extractor(FakeNano(), FakeOcr(""), NoCloud).recordFailure(id, null, "error: boom")
        assertEquals(CoffeeState.LOGGED, db.coffeeDao().get(id)!!.state)
        assertEquals(ExtractionSource.NONE, db.coffeeDao().get(id)!!.extractionSource)
    }

    @Test fun prepareFailureIsTransientNotTerminal() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(status = NanoStatus.DOWNLOADABLE, prepareFails = true)
        extractor(nano, FakeOcr("text"), NoCloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals(1, c.extractionAttempts)
        assertEquals(0, nano.calls)
    }

    @Test fun failedReReadKeepsNanoProvenance() = runBlocking {
        val id = seedCoffee()
        db.coffeeDao().get(id)!!.let { db.coffeeDao().update(it.copy(roaster = "Kept", extractionSource = ExtractionSource.NANO)) }
        val out = extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("text"), NoCloud).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Kept", c.roaster)
        assertEquals(ExtractionSource.NANO, c.extractionSource)
        assert(c.extractionMeta!!.startsWith("ocr-guess")) { c.extractionMeta }
    }

    // --- Human field edits win over an in-flight extraction ---

    /** Mirrors CoffeeRepository.edit: read-modify-write of the whole row with a fresh updatedAt. */
    private suspend fun edit(id: String, transform: (CoffeeEntity) -> CoffeeEntity) {
        val dao = db.coffeeDao()
        dao.get(id)!!.let { dao.update(transform(it).copy(updatedAt = System.currentTimeMillis())) }
    }

    @Test fun fieldEditsMadeDuringTheRunWinOverNano() = runBlocking {
        val id = seedCoffee()
        val dao = db.coffeeDao()
        val nano = FakeNano(answers = mutableListOf({
            // While Nano "thinks", the user fixes three fields by hand, sets a reminder and rates the coffee.
            edit(id) { it.copy(roaster = "Hand Typed", originCountry = "Colombia", tastingNotes = listOf("Cherry", "Cola")) }
            dao.setRemindAt(id, 123L, 1)
            edit(id) { it.copy(aroma = 4, flavor = 4, finish = 5, overall = 4.33f, notes = "lovely") }
            """{"roaster":"MODEL ROASTER","coffee_name":"Model Name","origin_country":"Ethiopia","tasting_notes":["jasmine"],"elevation_m":1900}"""
        }))
        val out = extractor(nano, FakeOcr("x"), NoCloud).run(id)
        val c = dao.get(id)!!
        // Edited mid-run: the user's values stand.
        assertEquals("Hand Typed", c.roaster)
        assertEquals("Colombia", c.originCountry)
        assertEquals(listOf("Cherry", "Cola"), c.tastingNotes)
        // Untouched: Nano fills them.
        assertEquals("Model Name", c.coffeeName)
        assertEquals(1900, c.elevationM)
        // Rating and reminder still survive.
        assertEquals(4.33f, c.overall)
        assertEquals("lovely", c.notes)
        assertEquals(123L, c.remindAt)
        assertEquals(CoffeeState.RATED, c.state)
        assertEquals(ExtractionSource.NANO, c.extractionSource)
        assert(c.extractionMeta!!.endsWith("; user-edited: roaster, originCountry, tastingNotes")) { c.extractionMeta }
        assertEquals(c.extractionMeta, out.note)
    }

    @Test fun editMadeBeforeTheRunIsOverwrittenByABetterNanoValue() = runBlocking {
        val id = seedCoffee()
        edit(id) { it.copy(roaster = "typo roaster") }
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"Northwind Coffee Roasters"}""" }))
        extractor(nano, FakeOcr("x"), NoCloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Northwind Coffee Roasters", c.roaster)
        assert(!c.extractionMeta!!.contains("user-edited")) { c.extractionMeta }
    }

    @Test fun failedReReadAfterAnEditKeepsTheEditAndPriorFacts() = runBlocking {
        val id = seedCoffee()
        edit(id) { it.copy(roaster = "Kept", coffeeName = "Also Kept", extractionSource = ExtractionSource.NANO) }
        val nano = FakeNano(answers = mutableListOf({
            edit(id) { it.copy(originCountry = "Peru") }
            throw IllegalStateException("boom")
        }))
        val out = extractor(nano, FakeOcr("text"), NoCloud).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Kept", c.roaster)
        assertEquals("Also Kept", c.coffeeName)
        assertEquals("Peru", c.originCountry)
        assertEquals(ExtractionSource.NANO, c.extractionSource)
        assertEquals(CoffeeState.LOGGED, c.state)
        assert(c.extractionMeta!!.startsWith("ocr-guess")) { c.extractionMeta }
    }

    @Test fun emptyReReadAfterAnEditKeepsTheEditAndPriorFacts() = runBlocking {
        val id = seedCoffee()
        edit(id) { it.copy(roaster = "Kept", extractionSource = ExtractionSource.NANO) }
        val nano = FakeNano(answers = mutableListOf({ edit(id) { it.copy(process = "Washed") }; "{}" }, { "{}" }))
        extractor(nano, FakeOcr("text"), NoCloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Kept", c.roaster)
        assertEquals("Washed", c.process)
        assertEquals(ExtractionSource.NANO, c.extractionSource)
    }

    // --- A garbled built-in answer's fields give way to a clean reading ---

    /** Not the JSON asked for: the "key: value" lines a broken answer falls back to. */
    private val garbledAnswer = """
        roaster: Lantern Roasters
        coffee_name: Finca Veleta
        varietal: Caturra
        tasting_notes: [Plum, Cocoa]
        roast_date: 3 November
    """.trimIndent()

    @Test fun aGarbledBuiltInAnswerRecordsWhichFieldsItFilled() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals(ExtractionSource.NANO, c.extractionSource)
        assertEquals(
            setOf("roaster", "coffeeName", "varietal", "tastingNotes", "roastDate"),
            ExtractionTrail.decode(c.extractionTrail).garbledFields,
        )
        // Read again keeps that record (and nothing else of the old trail) until the new read lands.
        repo.reextract(id)
        val queued = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.EXTRACTING, queued.state)
        assertEquals(ExtractionTrail(garbledFields = setOf("roaster", "coffeeName", "varietal", "tastingNotes", "roastDate")),
            ExtractionTrail.decode(queued.extractionTrail))
    }

    /**
     * The rule, end to end through Read again: a clean reading by the on-device reader replaces every
     * field a garbled built-in answer filled, and clears the ones it has nothing for. A field the user
     * edited is never touched, and a field an earlier clean reading filled stays.
     */
    @Test fun theReadersRereadReplacesOrClearsWhatOnlyAGarbledAnswerFilled() = runBlocking {
        val id = seedCoffee()
        // 1. A clean reading fills the producer and the region.
        extractor(FakeNano(answers = mutableListOf({ """{"producer":"Hollin Vale","region":"Huila"}""" })), FakeOcr("x")).run(id)
        // 2. Read again: the built-in AI answers garbled, filling five other fields and leaving those two.
        repo.reextract(id)
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        // 3. The person corrects one of the garbled fields by hand.
        repo.edit(id) { it.copy(varietal = listOf("Gesha")) }
        // 4. The reader reads it again (the device chose it) and finds only a roaster and the process.
        health.preferred = true
        repo.reextract(id)
        val reader = FakeLocal(answers = mutableListOf({ """{"roaster":"Signal Roasters","process":"Washed"}""" }))
        val out = extractor(FakeNano(), FakeOcr("x"), local = reader).run(id)
        assertEquals(ExtractionSource.LOCAL, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("the reader's value replaces the garbled one", "Signal Roasters", c.roaster)
        assertEquals("Washed", c.process)
        assertNull("a garbled name the reader didn't confirm is cleared", c.coffeeName)
        assertEquals(emptyList<String>(), c.tastingNotes)
        assertNull(c.roastDate)
        assertEquals("the user's edit stays", listOf("Gesha"), c.varietal)
        assertEquals("the earlier clean reading stays", "Hollin Vale", c.producer)
        assertEquals("Huila", c.region)
        assertEquals(ExtractionSource.LOCAL, c.extractionSource)
        assertEquals(emptySet<String>(), ExtractionTrail.decode(c.extractionTrail).garbledFields)
    }

    @Test fun aRereadThatIsGarbledTooOrOnlyAGuessKeepsWhatItHasNothingFor() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        // Garbled again, with only a roaster: the other garbled values stay, and stay marked.
        repo.reextract(id)
        extractor(FakeNano(answers = mutableListOf({ "roaster: Lantern" }, { "roaster: Lantern" })), FakeOcr("x")).run(id)
        var c = db.coffeeDao().get(id)!!
        assertEquals("Finca Veleta", c.coffeeName)
        assertEquals(setOf("roaster", "coffeeName", "varietal", "tastingNotes", "roastDate"), ExtractionTrail.decode(c.extractionTrail).garbledFields)
        // Every model fails and the label-text guess runs: a guess never replaces a model's reading.
        repo.reextract(id)
        extractor(FakeNano(answers = mutableListOf({ "nope" }, { "nope" })), FakeOcr("NORTHWIND")).run(id)
        c = db.coffeeDao().get(id)!!
        assertEquals("Finca Veleta", c.coffeeName)
        assertEquals(ExtractionSource.NANO, c.extractionSource)
        assert("coffeeName" in ExtractionTrail.decode(c.extractionTrail).garbledFields)
    }

    @Test fun aCleanRereadByTheBuiltInAiReplacesGarbledFieldsToo() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        repo.reextract(id)
        extractor(FakeNano(answers = mutableListOf({ """{"roaster":"Lantern Roasters","coffee_name":"Finca Veleta"}""" })), FakeOcr("x")).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Finca Veleta", c.coffeeName)
        assertEquals(emptyList<String>(), c.varietal)
        assertNull(c.roastDate)
        assertEquals(emptySet<String>(), ExtractionTrail.decode(c.extractionTrail).garbledFields)
    }

    /** A garbled answer that only repeats what a clean reading filled leaves those fields unmarked. */
    @Test fun aGarbledRereadThatRepeatsACleanValueLeavesItForTheNextCleanReading() = runBlocking {
        val id = seedCoffee()
        // 1. A clean reading fills the roaster and the coffee's name.
        extractor(FakeNano(answers = mutableListOf({ """{"roaster":"Lantern Roasters","coffee_name":"Finca Veleta"}""" })), FakeOcr("x")).run(id)
        // 2. Read again: the built-in AI answers garbled, repeating both and adding three fields.
        repo.reextract(id)
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        assertEquals(setOf("varietal", "tastingNotes", "roastDate"), ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail).garbledFields)
        // 3. The reader reads it again and finds the roaster and the process, but no name.
        health.preferred = true
        repo.reextract(id)
        val reader = FakeLocal(answers = mutableListOf({ """{"roaster":"Lantern Roasters","process":"Washed"}""" }))
        extractor(FakeNano(), FakeOcr("x"), local = reader).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("the earlier clean reading's name stays", "Finca Veleta", c.coffeeName)
        assertEquals("Washed", c.process)
        assertEquals(emptyList<String>(), c.varietal)
        assertNull(c.roastDate)
        assertEquals(emptySet<String>(), ExtractionTrail.decode(c.extractionTrail).garbledFields)
    }

    @Test fun aGarbledRereadThatRepeatsAMarkedValueKeepsItMarked() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        repo.reextract(id)
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        assertEquals(
            setOf("roaster", "coffeeName", "varietal", "tastingNotes", "roastDate"),
            ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail).garbledFields,
        )
    }

    /**
     * A garbled answer that changes a clean value is marked like any other field it filled. The
     * earlier clean value is gone by then: the next clean reading replaces the field, or clears it.
     */
    @Test fun aGarbledRereadThatChangesACleanValueIsReplacedOrClearedByTheNextCleanReading() = runBlocking {
        val id = seedCoffee()
        // 1. A clean reading fills the roaster and the coffee's name.
        extractor(FakeNano(answers = mutableListOf({ """{"roaster":"Harbor Roasters","coffee_name":"Lot Nine"}""" })), FakeOcr("x")).run(id)
        // 2. Read again: the built-in AI answers garbled with a different roaster and name.
        repo.reextract(id)
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        var c = db.coffeeDao().get(id)!!
        assertEquals("Finca Veleta", c.coffeeName)
        assert("roaster" in ExtractionTrail.decode(c.extractionTrail).garbledFields)
        assert("coffeeName" in ExtractionTrail.decode(c.extractionTrail).garbledFields)
        // 3. The reader finds a roaster and no name: the roaster is replaced, the name cleared.
        health.preferred = true
        repo.reextract(id)
        val reader = FakeLocal(answers = mutableListOf({ """{"roaster":"Signal Roasters","process":"Washed"}""" }))
        extractor(FakeNano(), FakeOcr("x"), local = reader).run(id)
        c = db.coffeeDao().get(id)!!
        assertEquals("Signal Roasters", c.roaster)
        assertNull("the earlier clean name does not come back", c.coffeeName)
        assertEquals(emptySet<String>(), ExtractionTrail.decode(c.extractionTrail).garbledFields)
    }

    /** A reader answer that was not the JSON asked for is garbled too: it clears nothing. */
    @Test fun aReaderAnswerThatIsNotJsonKeepsWhatAGarbledAnswerFilled() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        health.preferred = true
        repo.reextract(id)
        val loose = "roaster: Signal Roasters\nprocess: Washed"
        val reader = FakeLocal(answers = mutableListOf({ loose }, { loose }))
        val out = extractor(FakeNano(), FakeOcr("x"), local = reader).run(id)
        assertEquals(ExtractionSource.LOCAL, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Signal Roasters", c.roaster)
        assertEquals("the garbled name stays", "Finca Veleta", c.coffeeName)
        assertEquals(listOf("Caturra"), c.varietal)
        assertEquals(
            setOf("roaster", "coffeeName", "varietal", "tastingNotes", "roastDate", "process"),
            ExtractionTrail.decode(c.extractionTrail).garbledFields,
        )
    }

    @Test fun aSecondOpinionFromTheReaderThatIsNotJsonIsMarkedToo() = runBlocking {
        val id = seedCoffee()
        val loose = "roaster: Signal Roasters\nprocess: Washed"
        val reader = FakeLocal(answers = mutableListOf({ loose }, { loose }))
        val out = extractor(FakeNano(answers = mutableListOf({ "roaster: Lantern" }, { "roaster: Lantern" })), FakeOcr("x"), local = reader).run(id)
        assertEquals(ExtractionSource.LOCAL, out.source)
        assertEquals(setOf("roaster", "process"), ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail).garbledFields)
    }

    /** A model that takes a misread gram figure for the bag size is corrected by the ounces on the same line. */
    @Test fun aBagSizeTheLabelContradictsIsReadFromTheLabel() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"Lantern Roasters","bag_size_g":3409}""" }))
        extractor(nano, FakeOcr("LANTERN ROASTERS\nNet wt. 12OZ (3409) | Whole bean")).run(id)
        assertEquals(340, db.coffeeDao().get(id)!!.bagSizeG)
    }

    /** A bag size typed by hand is never corrected, whatever the label says. */
    @Test fun aHandEditedBagSizeIsNeverCorrectedByTheLabel() = runBlocking {
        val id = seedCoffee()
        val first = FakeNano(answers = mutableListOf({ """{"roaster":"Lantern Roasters"}""" }))
        extractor(first, FakeOcr("x")).run(id)
        repo.edit(id) { it.copy(bagSizeG = 3409) }
        repo.reextract(id)
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"Lantern Roasters","bag_size_g":3409}""" }))
        extractor(nano, FakeOcr("LANTERN ROASTERS\nNet wt. 12 oz (340 g)")).run(id)
        assertEquals(3409, db.coffeeDao().get(id)!!.bagSizeG)
    }

    /** The process, repeated as a tasting note by the model, is not a tasting note. */
    @Test fun theProcessRepeatedAsATastingNoteIsDropped() = runBlocking {
        val id = seedCoffee()
        val answer = """{"roaster":"Lantern Roasters","process":"Lychee Carbonic Maceration","tasting_notes":["Lychee carbonic maceration","Rose","Cola"]}"""
        extractor(FakeNano(answers = mutableListOf({ answer })), FakeOcr("x")).run(id)
        assertEquals(listOf("Rose", "Cola"), db.coffeeDao().get(id)!!.tastingNotes)
    }

    /** A Gemini answer that was not the JSON asked for is garbled like any model's: it clears nothing. */
    @Test fun aGeminiAnswerThatIsNotJsonKeepsWhatAGarbledAnswerFilled() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        repo.reextract(id)
        val loose = "roaster: Signal Roasters\nprocess: Washed"
        val cloud = FakeCloud(mutableListOf(loose, loose))
        val out = extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("x"), cloud).run(id)
        assertEquals(ExtractionSource.CLOUD, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Signal Roasters", c.roaster)
        assertEquals("the garbled name stays", "Finca Veleta", c.coffeeName)
        assertEquals(
            setOf("roaster", "coffeeName", "varietal", "tastingNotes", "roastDate", "process"),
            ExtractionTrail.decode(c.extractionTrail).garbledFields,
        )
    }

    /** A Gemini answer that came back as asked still counts as clean and replaces what a garbled one filled. */
    @Test fun aGeminiAnswerThatIsJsonReplacesOrClearsWhatAGarbledAnswerFilled() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(answers = mutableListOf({ garbledAnswer }, { garbledAnswer })), FakeOcr("x")).run(id)
        repo.reextract(id)
        val cloud = FakeCloud(mutableListOf("""{"roaster":"Signal Roasters","process":"Washed"}"""))
        extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("x"), cloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Signal Roasters", c.roaster)
        assertNull("a garbled name Gemini didn't confirm is cleared", c.coffeeName)
        assertEquals(emptySet<String>(), ExtractionTrail.decode(c.extractionTrail).garbledFields)
    }

    // --- The per-row edit marker closes the window before the run's first read ---

    private suspend fun seedLoggedNanoCoffee(): String {
        val id = seedCoffee()
        edit(id) { it.copy(state = CoffeeState.LOGGED, roaster = "Old Roaster", coffeeName = "Old Name", extractionSource = ExtractionSource.NANO) }
        return id
    }

    @Test fun editBetweenReadAgainAndTheFirstReadIsPreserved() = runBlocking {
        val id = seedLoggedNanoCoffee()
        repo.reextract(id)
        // The row is EXTRACTING but this run has not read it yet (queued behind another coffee, say).
        repo.edit(id) { it.copy(roaster = "Hand Typed") }
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"MODEL ROASTER","coffee_name":"Model Name"}""" }))
        val out = extractor(nano, FakeOcr("x"), NoCloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Hand Typed", c.roaster)
        assertEquals("Model Name", c.coffeeName)
        assertEquals(CoffeeState.LOGGED, c.state)
        assertEquals(ExtractionSource.NANO, c.extractionSource)
        assert(c.extractionMeta!!.endsWith("; user-edited: roaster")) { c.extractionMeta }
        assertEquals(c.extractionMeta, out.note)
    }

    @Test fun editAfterATransientFailureIsPreservedByTheNextAttempt() = runBlocking {
        val id = seedLoggedNanoCoffee()
        repo.reextract(id)
        val nano = FakeNano(answers = mutableListOf({ throw TransientExtractionException("model downloading") }, { """{"roaster":"MODEL ROASTER","region":"Huila"}""" }))
        val extractor = extractor(nano, FakeOcr("x"), NoCloud)
        extractor.run(id)
        assertEquals(CoffeeState.EXTRACTING, db.coffeeDao().get(id)!!.state)
        // Minutes pass while the model downloads; the user fixes the roaster by hand.
        repo.edit(id) { it.copy(roaster = "Hand Typed") }
        extractor.run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Hand Typed", c.roaster)
        assertEquals("Huila", c.region)
        assertEquals(CoffeeState.LOGGED, c.state)
        assert(c.extractionMeta!!.endsWith("; user-edited: roaster")) { c.extractionMeta }
    }

    /**
     * The Reread button must not overwrite fields the user already edited: the tap keeps the
     * hand-edit markers, so the new read leaves those fields alone.
     */
    @Test fun editBeforeRereadIsKeptByTheNewRead() = runBlocking {
        val id = seedLoggedNanoCoffee()
        repo.edit(id) { it.copy(roaster = "Hand Typed") }
        repo.reextract(id)
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"Northwind Coffee Roasters","coffee_name":"New Name"}""" }))
        extractor(nano, FakeOcr("x"), NoCloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Hand Typed", c.roaster)
        assertEquals("New Name", c.coffeeName)
        assert(c.extractionMeta!!.endsWith("; user-edited: roaster")) { c.extractionMeta }
    }

    @Test fun readAgainWithoutAnEditLetsNanoFillEveryField() = runBlocking {
        val id = seedLoggedNanoCoffee()
        repo.reextract(id)
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"New Roaster","coffee_name":"New Name","process":"WASHED"}""" }))
        extractor(nano, FakeOcr("x"), NoCloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("New Roaster", c.roaster)
        assertEquals("New Name", c.coffeeName)
        assertEquals("Washed", c.process)
        assert(!c.extractionMeta!!.contains("user-edited")) { c.extractionMeta }
    }

    @Test fun editDuringTheRunThroughTheRepositoryIsListedOnce() = runBlocking {
        val id = seedLoggedNanoCoffee()
        repo.reextract(id)
        val nano = FakeNano(answers = mutableListOf({
            repo.edit(id) { it.copy(roaster = "Hand Typed", region = "Huila") }
            """{"roaster":"MODEL ROASTER","coffee_name":"Model Name","region":"Narino"}"""
        }))
        extractor(nano, FakeOcr("x"), NoCloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Hand Typed", c.roaster)
        assertEquals("Huila", c.region)
        assertEquals("Model Name", c.coffeeName)
        // Both the marker and the baseline compare see this edit; the note names each field once.
        assert(c.extractionMeta!!.endsWith("; user-edited: roaster, region")) { c.extractionMeta }
    }

    @Test fun everyEditableFieldIsHonoredByName() = runBlocking {
        val id = seedLoggedNanoCoffee()
        repo.reextract(id)
        repo.edit(id) {
            it.copy(
                roaster = "R", coffeeName = "N", originCountry = "O", region = "G", producer = "P", process = "W",
                varietal = listOf("V"), elevationM = 1, roastLevel = "L", roastDate = "D", bagSizeG = 2,
                tastingNotes = listOf("T"), bagDescription = "B", sellerDescription = "S",
            )
        }
        val nano = FakeNano(answers = mutableListOf({
            """{"roaster":"x","coffee_name":"x","origin_country":"x","region":"x","producer":"x","process":"x",""" +
                """"varietal":["x"],"elevation_m":9,"roast_level":"x","roast_date":"2026-01-01","bag_size_g":9,""" +
                """"tasting_notes":["x"],"bag_description":"x","seller_description":"x"}"""
        }))
        extractor(nano, FakeOcr("x"), NoCloud).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals(
            listOf<Any?>("R", "N", "O", "G", "P", "W", listOf("V"), 1, "L", "D", 2, listOf("T"), "B", "S"),
            listOf(c.roaster, c.coffeeName, c.originCountry, c.region, c.producer, c.process, c.varietal, c.elevationM, c.roastLevel, c.roastDate, c.bagSizeG, c.tastingNotes, c.bagDescription, c.sellerDescription),
        )
        assert(
            c.extractionMeta!!.endsWith(
                "; user-edited: roaster, coffeeName, originCountry, region, producer, process, varietal, elevationM, roastLevel, roastDate, bagSizeG, tastingNotes, bagDescription, sellerDescription"
            )
        ) { c.extractionMeta }
    }

    @Test fun rowDeletedDuringTheRunStaysDeleted() = runBlocking {
        val id = seedCoffee()
        val dao = db.coffeeDao()
        val nano = FakeNano(answers = mutableListOf({
            dao.get(id)!!.let { dao.delete(it) }
            """{"roaster":"X"}"""
        }))
        val out = extractor(nano, FakeOcr("x"), NoCloud).run(id)
        assertEquals(ExtractionSource.NANO, out.source)
        assertNull(dao.get(id))
    }

    // --- Cloud tier ---

    @Test fun nanoUnavailableWithAKeyUsesTheCloud() = runBlocking {
        val id = seedCoffee()
        val cloud = FakeCloud(mutableListOf("""{"roaster":"CLOUDHOLLOW ROASTERS","coffee_name":"Sky"}"""))
        val out = extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("text"), cloud).run(id)
        assertEquals(ExtractionSource.CLOUD, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Cloudhollow Roasters", c.roaster)
        assertEquals(ExtractionSource.CLOUD, c.extractionSource)
        assert(c.extractionMeta!!.startsWith("cloud:fake-cloud"))
        assertEquals(1, cloud.calls)
    }

    @Test fun cloudFailureStillFallsBackToOcr() = runBlocking {
        val id = seedCoffee()
        val cloud = object : dev.roastbook.app.cloud.CloudTier {
            override suspend fun available() = true
            override suspend fun generate(images: List<Bitmap>, prompt: String): String = throw java.io.IOException("offline")
            override suspend fun modelName() = "fake-cloud"
        }
        val out = extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("text"), cloud).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        assert(db.coffeeDao().get(id)!!.extractionMeta!!.contains("cloud threw IOException (network)"))
    }

    @Test fun nanoSuccessNeverCallsTheCloud() = runBlocking {
        val id = seedCoffee()
        val cloud = FakeCloud(mutableListOf("""{"roaster":"X"}"""))
        extractor(FakeNano(answers = mutableListOf({ """{"roaster":"NANO"}""" })), FakeOcr("t"), cloud).run(id)
        assertEquals(0, cloud.calls)
        assertEquals(ExtractionSource.NANO, db.coffeeDao().get(id)!!.extractionSource)
    }

    @Test fun sellerScreenshotIsLabeledInThePrompt() {
        val prompt = ExtractionPrompt.build(listOf(OcrPage("photo 1 (front)", false, "A"), OcrPage("photo 2 (seller)", true, "B")))
        assert(prompt.contains("screenshot of the SELLER"))
        assert(prompt.contains("one of them is a screenshot"))
    }

    // --- The full chain: bounded Nano waits, the on-device reader, the guess, background runs ---

    private val coffeeText = "NORTHWIND COFFEE ROASTERS\nKenya\nProcess: Washed\nSL28, SL34\n1,850 masl\nTasting notes: blackcurrant, grapefruit, cane sugar\n12 oz (340 g)"

    @Test fun prepareThatNeverReturnsParksTheRowForThreeMinutes() = runBlocking {
        val id = seedCoffee()
        var trailDuringPrepare: String? = null
        val nano = FakeNano(status = NanoStatus.DOWNLOADABLE, onPrepare = {
            trailDuringPrepare = db.coffeeDao().get(id)!!.extractionTrail
            kotlinx.coroutines.awaitCancellation()
        })
        val out = extractor(nano, FakeOcr("text")).run(id)
        assertEquals(ExtractionSource.NONE, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals(1, c.extractionAttempts)
        assertEquals(now + 3 * 60_000L, c.nextAttemptAt)
        // The card shows the first-download state while waiting and while parked.
        assertEquals("nano=preparing", trailDuringPrepare)
        assertEquals(TierOutcome.PREPARING, ExtractionTrail.decode(c.extractionTrail)[Tier.NANO])
        assertEquals(0, nano.calls)
    }

    @Test fun secondMissWaitsFiveMinutesAndTheThirdFallsThroughToTheCloud() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(status = NanoStatus.DOWNLOADABLE, onPrepare = { kotlinx.coroutines.awaitCancellation() })
        val cloud = FakeCloud(mutableListOf("""{"roaster":"Stillwick Roasters","coffee_name":"Beacon"}"""))
        val ex = extractor(nano, FakeOcr("text"), cloud)
        ex.run(id)
        assertEquals(now + 3 * 60_000L, db.coffeeDao().get(id)!!.nextAttemptAt)
        // Not due yet: the queue skips it.
        assertNull(ex.runIfDue(id))
        now += 3 * 60_000L
        ex.runIfDue(id)
        val second = db.coffeeDao().get(id)!!
        assertEquals(2, second.extractionAttempts)
        assertEquals(now + 5 * 60_000L, second.nextAttemptAt)
        assertEquals(0, cloud.calls)
        now += 5 * 60_000L
        val out = ex.runIfDue(id)!!
        assertEquals(ExtractionSource.CLOUD, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.LOGGED, c.state)
        assertEquals("Stillwick Roasters", c.roaster)
        assertNull(c.nextAttemptAt)
        assertEquals(TierOutcome.NOT_READY, ExtractionTrail.decode(c.extractionTrail)[Tier.NANO])
        assert(c.extractionMeta!!.contains("nano gave up after 3 attempts")) { c.extractionMeta }
        assertEquals(listOf(false), health.records)
    }

    @Test fun thirdPrepareMissWithoutAKeyEndsWithTheGuess() = runBlocking {
        val id = seedCoffee()
        db.coffeeDao().get(id)!!.let { db.coffeeDao().update(it.copy(extractionAttempts = 2)) }
        val out = extractor(FakeNano(status = NanoStatus.DOWNLOADABLE, prepareFails = true), FakeOcr(coffeeText)).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Northwind Coffee Roasters", c.roaster)
        assertEquals("Kenya", c.originCountry)
    }

    @Test fun statusThatHangsParksInsteadOfBlockingTheQueue() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(onStatus = { kotlinx.coroutines.awaitCancellation() })
        extractor(nano, FakeOcr("text")).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals(now + 3 * 60_000L, c.nextAttemptAt)
    }

    @Test fun generateThatHangsTimesOutAndFallsThrough() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ kotlinx.coroutines.awaitCancellation() }))
        val out = extractor(nano, FakeOcr("text")).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        assertEquals(TierOutcome.FAILED, ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail)[Tier.NANO])
        assertEquals(listOf(false), health.records)
    }

    @Test fun runIfDueSkipsRowsThatAreNoLongerWaiting() = runBlocking {
        val id = seedCoffee()
        db.coffeeDao().get(id)!!.let { db.coffeeDao().update(it.copy(state = CoffeeState.LOGGED)) }
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"X"}""" }))
        assertNull(extractor(nano, FakeOcr("t")).runIfDue(id))
        assertEquals(0, nano.calls)
    }

    @Test fun nanoSuccessClearsTheFailingFlag() = runBlocking {
        val id = seedCoffee()
        health.failing = true
        extractor(FakeNano(answers = mutableListOf({ """{"roaster":"X"}""" })), FakeOcr("t")).run(id)
        assertEquals(listOf(true), health.records)
        assert(!health.failing)
    }

    @Test fun localReaderReadsWhenNanoIsUnavailable() = runBlocking {
        val id = seedCoffee()
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"LARKMOOR ROASTERS","coffee_name":"Ember","elevation_m":1900}""" }))
        val out = extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("t"), local = local).run(id)
        assertEquals(ExtractionSource.LOCAL, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Larkmoor Roasters", c.roaster)
        assertEquals(1900, c.elevationM)
        assertEquals(ExtractionSource.LOCAL, c.extractionSource)
        assert(c.extractionMeta!!.startsWith("local:fake-local")) { c.extractionMeta }
        assertEquals("nano=unsupported;local=read", c.extractionTrail)
    }

    @Test fun localReaderRunsBeforeTheCloud() = runBlocking {
        val id = seedCoffee()
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"On Device"}""" }))
        val cloud = FakeCloud(mutableListOf("""{"roaster":"Cloud"}"""))
        extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("t"), cloud, local).run(id)
        assertEquals("On Device", db.coffeeDao().get(id)!!.roaster)
        assertEquals(0, cloud.calls)
    }

    @Test fun nanoFailureFallsThroughToTheLocalReader() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ throw IllegalStateException("npu") }))
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Sable & Rye"}""" }))
        val out = extractor(nano, FakeOcr("t"), local = local).run(id)
        assertEquals(ExtractionSource.LOCAL, out.source)
        assertEquals("nano=failed;local=read", db.coffeeDao().get(id)!!.extractionTrail)
        assertEquals(listOf(false), health.records)
    }

    @Test fun localFailureFallsThroughToTheCloud() = runBlocking {
        val id = seedCoffee()
        val local = FakeLocal(answers = mutableListOf({ throw IllegalStateException("engine") }))
        val cloud = FakeCloud(mutableListOf("""{"roaster":"Cloud"}"""))
        val out = extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("t"), cloud, local).run(id)
        assertEquals(ExtractionSource.CLOUD, out.source)
        assertEquals("nano=unsupported;local=failed;cloud=read", db.coffeeDao().get(id)!!.extractionTrail)
    }

    @Test fun localUnparseableTwiceThenGuess() = runBlocking {
        val id = seedCoffee()
        val local = FakeLocal(answers = mutableListOf({ "not json" }, { "still not" }))
        val out = extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr(coffeeText), local = local).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        assertEquals(2, local.calls)
        assertEquals(TierOutcome.FAILED, ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail)[Tier.LOCAL])
    }

    @Test fun localEmptyAnswerIsRecordedAsEmpty() = runBlocking {
        val id = seedCoffee()
        val local = FakeLocal(answers = mutableListOf({ "{}" }, { "{}" }))
        extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("t"), local = local).run(id)
        assertEquals(TierOutcome.EMPTY, ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail)[Tier.LOCAL])
    }

    @Test fun localRepairsCommaSlipsInItsAnswer() = runBlocking {
        val id = seedCoffee()
        val local = FakeLocal(answers = mutableListOf({ "{\n\"roaster\": \"Northwind\"\n\"bag_size_g\": 340,,\n}" }))
        val out = extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("t"), local = local).run(id)
        assertEquals(ExtractionSource.LOCAL, out.source)
        assertEquals(340, db.coffeeDao().get(id)!!.bagSizeG)
        assertEquals(1, local.calls)
    }

    @Test fun notDownloadedReaderIsRecordedForTheCard() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr(coffeeText), local = FakeLocal(LocalAvailability.NOT_DOWNLOADED)).run(id)
        val t = ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail)
        assertEquals(TierOutcome.ABSENT, t[Tier.LOCAL])
        assertEquals(TierOutcome.OFF, t[Tier.CLOUD])
        assert(t.guessedFields >= 5) { t.encode() }
    }

    @Test fun backgroundLeavesAWorkingNanoForTheForeground() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"X"}""" }))
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Y"}""" }))
        val out = extractor(nano, FakeOcr("t"), local = local).run(id, RunMode.BACKGROUND)
        assertEquals(ExtractionSource.NONE, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals(0, c.extractionAttempts)
        assertEquals(0, nano.calls)
        assertEquals(0, local.calls)
    }

    @Test fun backgroundSkipsAFailingNanoAndReadsLocally() = runBlocking {
        val id = seedCoffee()
        health.failing = true
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"X"}""" }))
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Larkmoor"}""" }))
        val out = extractor(nano, FakeOcr("t"), local = local).run(id, RunMode.BACKGROUND)
        assertEquals(ExtractionSource.LOCAL, out.source)
        assertEquals(0, nano.calls)
        assertEquals("nano=skipped;local=read", db.coffeeDao().get(id)!!.extractionTrail)
    }

    @Test fun backgroundReadsLocallyWhenNanoIsUnavailable() = runBlocking {
        val id = seedCoffee()
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Larkmoor"}""" }))
        assertEquals(ExtractionSource.LOCAL, extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("t"), local = local).runIfDue(id, RunMode.BACKGROUND)!!.source)
    }

    @Test fun guessFillsFieldsFromTheLabelText() = runBlocking {
        val id = seedCoffee()
        val out = extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr(coffeeText)).run(id)
        assertEquals(ExtractionSource.OCR, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Northwind Coffee Roasters", c.roaster)
        assertNull(c.coffeeName)
        assertEquals("Kenya", c.originCountry)
        assertEquals("Washed", c.process)
        assertEquals(listOf("SL28", "SL34"), c.varietal)
        assertEquals(1850, c.elevationM)
        assertEquals(listOf("Blackcurrant", "Grapefruit", "Cane Sugar"), c.tastingNotes)
        assertEquals(340, c.bagSizeG)
        assert(c.extractionMeta!!.startsWith("ocr-guess (7 fields)")) { c.extractionMeta }
        assertEquals(7, ExtractionTrail.decode(c.extractionTrail).guessedFields)
    }

    @Test fun guessNeverReplacesAModelReading() = runBlocking {
        val id = seedCoffee()
        db.coffeeDao().get(id)!!.let { db.coffeeDao().update(it.copy(roaster = "Model Roaster", extractionSource = ExtractionSource.LOCAL)) }
        extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr(coffeeText)).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Model Roaster", c.roaster)
        // Not even the blanks: a model-read coffee is not mixed with guesses.
        assertNull(c.originCountry)
        assertEquals(ExtractionSource.LOCAL, c.extractionSource)
    }

    @Test fun guessFillsOnlyBlankFieldsOfAnEarlierGuess() = runBlocking {
        val id = seedCoffee()
        db.coffeeDao().get(id)!!.let { db.coffeeDao().update(it.copy(roaster = "Kept", extractionSource = ExtractionSource.OCR)) }
        extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr(coffeeText)).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Kept", c.roaster)
        assertEquals("Kenya", c.originCountry)
        // A second guess over the same text still reports its fields as guessed, not "nothing matched".
        extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr(coffeeText)).run(id)
        assertEquals(6, ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail).guessedFields)
    }

    @Test fun guessRespectsAHandEditedField() = runBlocking {
        val id = seedLoggedNanoCoffee()
        db.coffeeDao().get(id)!!.let { db.coffeeDao().update(it.copy(extractionSource = ExtractionSource.NONE, roaster = null, coffeeName = null)) }
        repo.edit(id) { it.copy(originCountry = "Peru") }
        repo.reextract(id)
        extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr(coffeeText)).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Peru", c.originCountry)
        assertEquals("Northwind Coffee Roasters", c.roaster)
    }

    @Test fun rereadAfterAnEditNeverOverwritesItWithAnyTier() = runBlocking {
        val id = seedLoggedNanoCoffee()
        repo.edit(id) { it.copy(roaster = "Hand Typed", process = "Honey") }
        repo.reextract(id)
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Model","process":"Washed","region":"Nyeri"}""" }))
        extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("t"), local = local).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Hand Typed", c.roaster)
        assertEquals("Honey", c.process)
        assertEquals("Nyeri", c.region)
        assertEquals(ExtractionSource.LOCAL, c.extractionSource)
    }

    @Test fun aRereadWhoseEverySuggestionIsAHandEditedFieldKeepsTheEarlierReader() = runBlocking {
        val id = seedLoggedNanoCoffee()
        repo.edit(id) { it.copy(roaster = "Hand Typed", process = "Honey") }
        repo.reextract(id)
        // The on-device reader answers, but only for the two fields the person typed: nothing it
        // suggested is kept, so the coffee is not "read by" it.
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Model","process":"Washed"}""" }))
        extractor(FakeNano(status = NanoStatus.UNAVAILABLE), FakeOcr("t"), local = local).run(id)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Hand Typed", c.roaster)
        assertEquals("Honey", c.process)
        assertEquals(ExtractionSource.NANO, c.extractionSource)
        assertEquals(
            R.string.what_kept_earlier,
            WhatHappenedRules.explain(c.extractionSource, ExtractionTrail.decode(c.extractionTrail), hasOcrText = true, readerOffered = false, hasKey = false)?.line,
        )
    }

    @Test fun schedulerErrorAtTheCapFinishesWithTheGuess() = runBlocking {
        val id = seedCoffee()
        db.coffeeDao().get(id)!!.let { db.coffeeDao().update(it.copy(extractionAttempts = 2)) }
        extractor(FakeNano(), FakeOcr("")).recordFailure(id, "--- photo 1 (front) ---\n$coffeeText", "error: boom")
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.LOGGED, c.state)
        assertEquals(ExtractionSource.OCR, c.extractionSource)
        assertEquals("Kenya", c.originCountry)
    }

    @Test fun nextDueAtReportsTheEarliestParkedRetry() = runBlocking {
        val id = seedCoffee()
        val ex = extractor(FakeNano(status = NanoStatus.DOWNLOADABLE, prepareFails = true), FakeOcr("t"))
        ex.run(id)
        assertEquals(now + 3 * 60_000L, ex.nextDueAt(listOf(id)))
    }

    @Test fun quickPrepareDoesNotFlashTheFirstDownloadState() = runBlocking {
        val id = seedCoffee()
        var trailWhileReading: String? = "unset"
        val nano = FakeNano(status = NanoStatus.DOWNLOADABLE, answers = mutableListOf({
            trailWhileReading = db.coffeeDao().get(id)!!.extractionTrail
            """{"roaster":"Northwind"}"""
        }))
        val ex = Extractor(db, photos, nano, FakeOcr("t"), NoCloud, NoLocal, health, clock, timeouts.copy(preparingBadgeMs = 500))
        assertEquals(ExtractionSource.NANO, ex.run(id).source)
        assertEquals(1, nano.prepares)
        assertNull(trailWhileReading)
    }

    @Test fun backgroundWithAFailingNanoButNoReaderWaitsForTheForeground() = runBlocking {
        val id = seedCoffee()
        health.failing = true
        val cloud = FakeCloud(mutableListOf("""{"roaster":"Cloud"}"""))
        val out = extractor(FakeNano(), FakeOcr(coffeeText), cloud, FakeLocal(LocalAvailability.NOT_DOWNLOADED)).run(id, RunMode.BACKGROUND)
        assertEquals(ExtractionSource.NONE, out.source)
        assertEquals(0, cloud.calls)
        val c = db.coffeeDao().get(id)!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals(0, c.extractionAttempts)
    }

    @Test fun anUnparseableBagDoesNotMarkNanoAsFailing() = runBlocking {
        val id = seedCoffee()
        extractor(FakeNano(answers = mutableListOf({ "nope" }, { "nope" })), FakeOcr("t")).run(id)
        assert(health.records.isEmpty()) { health.records }
        assert(!health.failing)
    }

    // --- The reader chosen in Settings, and a second opinion on a thin built-in reading ---

    @Test fun theChosenReaderReadsInsteadOfNano() = runBlocking {
        val id = seedCoffee()
        health.preferred = true
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"Larkmoor"}""" }))
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Northwind","coffee_name":"Copper Ridge"}""" }))
        val out = extractor(nano, FakeOcr("t"), local = local).run(id)
        assertEquals(ExtractionSource.LOCAL, out.source)
        assertEquals(0, nano.calls)
        assertEquals("Northwind", db.coffeeDao().get(id)!!.roaster)
        assertEquals(TierOutcome.SKIPPED, ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail)[Tier.NANO])
    }

    @Test fun theChosenReaderRunsInTheBackgroundToo() = runBlocking {
        val id = seedCoffee()
        health.preferred = true
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Northwind"}""" }))
        assertEquals(ExtractionSource.LOCAL, extractor(FakeNano(), FakeOcr("t"), local = local).runIfDue(id, RunMode.BACKGROUND)!!.source)
    }

    @Test fun theChoiceWaitsForTheDownload() = runBlocking {
        val id = seedCoffee()
        health.preferred = true
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"Larkmoor"}""" }))
        val out = extractor(nano, FakeOcr("t"), local = FakeLocal(availability = LocalAvailability.NOT_DOWNLOADED)).run(id)
        assertEquals(ExtractionSource.NANO, out.source)
        assertEquals(1, nano.calls)
    }

    @Test fun aThinNanoReadingGivesWayToABetterReaderReading() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"producer":"Hollin Vale","process":"Washed"}""" }))
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Northwind","coffee_name":"Copper Ridge","origin_country":"Peru","process":"Washed"}""" }))
        val out = extractor(nano, FakeOcr("t"), local = local).run(id)
        assertEquals(ExtractionSource.LOCAL, out.source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Northwind", c.roaster)
        assertNull(c.producer)
        assertEquals(ExtractionSource.LOCAL, c.extractionSource)
    }

    @Test fun aThinNanoReadingIsKeptWhenTheReaderDoesNoBetter() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"producer":"Hollin Vale","process":"Washed"}""" }))
        val local = FakeLocal(answers = mutableListOf({ """{"process":"Natural"}""" }))
        val out = extractor(nano, FakeOcr("t"), local = local).run(id)
        assertEquals(ExtractionSource.NANO, out.source)
        assertEquals("Hollin Vale", db.coffeeDao().get(id)!!.producer)
        assertEquals(1, local.calls)
    }

    @Test fun aThinNanoReadingWithoutTheReaderIsKeptAndSaysNothingAboutTheDevice() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"producer":"Hollin Vale"}""" }))
        val local = FakeLocal(availability = LocalAvailability.NOT_DOWNLOADED)
        assertEquals(ExtractionSource.NANO, extractor(nano, FakeOcr("t"), local = local).run(id).source)
        assertEquals(0, local.calls)
        assertEquals(listOf(true), health.records)
    }

    @Test fun theChosenReaderFindingNothingIsNotCalledMissingBuiltInAi() = runBlocking {
        val id = seedCoffee()
        health.preferred = true
        val local = FakeLocal(answers = mutableListOf({ "{}" }, { "{}" }))
        extractor(FakeNano(), FakeOcr("Northwind Coffee Roasters"), local = local).run(id)
        val trail = ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail)
        val w = WhatHappenedRules.explain(ExtractionSource.OCR, trail, hasOcrText = true, readerOffered = true, hasKey = false)!!
        assertEquals(dev.roastbook.app.R.string.what_ocr_local_failed, w.line)
    }

    @Test fun aFullNanoReadingIsNotSecondGuessed() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"Larkmoor","process":"Washed"}""" }))
        val local = FakeLocal()
        assertEquals(ExtractionSource.NANO, extractor(nano, FakeOcr("t"), local = local).run(id).source)
        assertEquals(0, local.calls)
        assertEquals(listOf(true), health.records)
    }

    @Test fun aMalformedNanoAnswerIsKeptButMarkedPoor() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ "{\n\"roaster\": \"Larkmoor\",\n\"producer\": null \"Hollin Vale\"\n}" }))
        assertEquals(ExtractionSource.NANO, extractor(nano, FakeOcr("t")).run(id).source)
        val c = db.coffeeDao().get(id)!!
        assertEquals("Larkmoor", c.roaster)
        assertNull(c.producer)
        assertTrue(ExtractionTrail.decode(c.extractionTrail).nanoPoor)
    }

    @Test fun aWellFormedNanoAnswerWithARoasterIsNotPoor() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"Larkmoor"}""" }))
        extractor(nano, FakeOcr("t")).run(id)
        assertEquals(false, ExtractionTrail.decode(db.coffeeDao().get(id)!!.extractionTrail).nanoPoor)
    }

    @Test fun aMalformedNanoAnswerGetsTheReadersSecondOpinion() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ "{\n\"roaster\": \"Larkmoor\",\n\"producer\": null \"Hollin Vale\"\n}" }))
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Larkmoor","coffee_name":"Copper Ridge","process":"Washed"}""" }))
        assertEquals(ExtractionSource.LOCAL, extractor(nano, FakeOcr("t"), local = local).run(id).source)
        assertEquals("Copper Ridge", db.coffeeDao().get(id)!!.coffeeName)
    }

    @Test fun onATieTheReadersCleanReadingBeatsAMalformedBuiltInOne() = runBlocking {
        val id = seedCoffee()
        // Two fields each; the built-in answer is broken JSON, the reader's is the JSON asked for.
        val nano = FakeNano(answers = mutableListOf({ "{\n\"roaster\": \"Larkmoor\",\n\"process\": \"Washed\"\n\"producer\": null \"x\"\n}" }))
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Larkmoor","coffee_name":"Copper Ridge"}""" }))
        assertEquals(ExtractionSource.LOCAL, extractor(nano, FakeOcr("t"), local = local).run(id).source)
        assertEquals("Copper Ridge", db.coffeeDao().get(id)!!.coffeeName)
    }

    @Test fun onATieAWellFormedThinBuiltInReadingIsKept() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"producer":"Hollin Vale","process":"Washed"}""" }))
        val local = FakeLocal(answers = mutableListOf({ """{"process":"Natural","varietal":["Geisha"]}""" }))
        assertEquals(ExtractionSource.NANO, extractor(nano, FakeOcr("t"), local = local).run(id).source)
    }

    @Test fun aMalformedBuiltInReadingWithMoreFieldsStillBeatsACleanReaderReading() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ "roaster: Larkmoor\ncoffee_name: Copper Ridge\nprocess: Washed" }))
        val local = FakeLocal(answers = mutableListOf({ """{"roaster":"Larkmoor"}""" }))
        assertEquals(ExtractionSource.NANO, extractor(nano, FakeOcr("t"), local = local).run(id).source)
        assertEquals("Copper Ridge", db.coffeeDao().get(id)!!.coffeeName)
    }

    @Test fun aPoorBuiltInReadingWaitsForAReaderOnItsWay() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"producer":"Hollin Vale"}""" }))
        extractor(nano, FakeOcr("t"), local = FakeLocal(availability = LocalAvailability.NOT_DOWNLOADED)).run(id)
        assertEquals(listOf(id), health.waitingForReader)
    }

    @Test fun aGoodBuiltInReadingDoesNotWaitForTheReader() = runBlocking {
        val id = seedCoffee()
        val nano = FakeNano(answers = mutableListOf({ """{"roaster":"Larkmoor"}""" }))
        extractor(nano, FakeOcr("t"), local = FakeLocal(availability = LocalAvailability.NOT_DOWNLOADED)).run(id)
        assertEquals(emptyList<String>(), health.waitingForReader)
    }
}
