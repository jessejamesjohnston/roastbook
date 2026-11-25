package dev.roastbook.app.extraction

import android.graphics.Bitmap
import android.util.Log
import androidx.room.withTransaction
import dev.roastbook.app.BuildConfig
import dev.roastbook.app.cloud.CloudFailure
import dev.roastbook.app.cloud.CloudTier
import dev.roastbook.app.data.db.CoffeeDao
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.util.runCatchingCancellable
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** One run's outcome, for logging and for tests. */
data class ExtractionOutcome(val source: ExtractionSource, val facts: BagFacts?, val note: String)

/**
 * The extraction state machine. Every branch is a plain suspend path so it can be unit-tested with
 * fake tiers. The tiers are tried in order, and each one that does not produce fields hands over to
 * the next ("fall through"); [ExtractionTrail] records what each did so the card can say why:
 *
 *   photos → OCR (always, full resolution)
 *     1. Nano (the system model; images + OCR text) → parse → [retry once, strict] → NANO
 *          model still downloading → the row is PARKED (stays EXTRACTING) and retried after 3, then
 *          5 minutes; the third miss falls through. prepare() and every call are time-bounded, so a
 *          download that never reports back cannot hold the queue.
 *          background blocked → parked until the next foreground; the third miss falls through.
 *          skipped while the person chose the reader in Settings and it is downloaded.
 *          a thin reading (neither roaster nor coffee) is checked against the reader when it is
 *          downloaded, and the reading with more fields is kept (NANO or LOCAL).
 *     2. the on-device reader (downloaded open model, runs in-process) → LOCAL
 *     3. the user's Gemini key → CLOUD
 *     4. a rule-based guess over the OCR text (fills blank fields only) → OCR
 *     nothing recognizable at all → NONE
 *
 * The on-device reader runs before the cloud: it keeps the bag on the phone and costs nothing, and
 * a user who downloaded it asked for exactly that.
 *
 * In [RunMode.BACKGROUND] (the worker that continues reads after the app leaves the screen) Nano
 * is never called, because AICore refuses background callers: a row whose Nano may still work is
 * left for the foreground without counting an attempt.
 *
 * Every write re-reads the row inside a transaction and touches only extraction-owned columns:
 * a rating, reminder or edit made while a read was running must survive. That includes a hand
 * edit to an extracted field, found two ways: the row's own [CoffeeEntity.userEdited] list (the
 * repository records every field the Detail editors ever changed, and a Reread does not clear it)
 * and a compare of the fresh row with the snapshot taken when the run began (catches a write that
 * bypassed the repository). A field found either way keeps the user's value and the tier's
 * suggestion for it is dropped. The kept fields are listed in extractionMeta so the source tier is
 * not credited with them.
 */
@Singleton
class Extractor @Inject constructor(
    private val db: RoastbookDatabase,
    private val photos: PhotoStore,
    private val nano: NanoTier,
    private val ocr: OcrTier,
    private val cloud: CloudTier,
    private val local: LocalTier,
    private val nanoHealth: NanoHealth,
    private val clock: Clock,
    private val timeouts: ExtractionTimeouts,
) {
    private val dao: CoffeeDao get() = db.coffeeDao()

    /** One read at a time, shared by the foreground scheduler and the background worker. */
    private val queue = Mutex()

    /**
     * Rows whose attempt was counted up front, before a call that can take the whole process down
     * (the on-device model needs gigabytes). A read that kills the process is still counted, so a
     * bag that always does it reaches the attempt cap and falls back instead of looping on every
     * start; a read that returns normally is not counted twice.
     */
    private val countedUpFront = Collections.synchronizedSet(mutableSetOf<String>())

    /** The library generation ([PhotoStore.generation]) each coffee's current run read from. */
    private val startedIn = ConcurrentHashMap<String, Long>()

    /**
     * Runs [coffeeId] if it still needs reading and its parked retry (if any) is due; null when it
     * was skipped. Both runners call this, so a row picked up by one is re-checked under the lock
     * before the other can read it again.
     */
    suspend fun runIfDue(coffeeId: String, mode: RunMode = RunMode.FOREGROUND): ExtractionOutcome? = queue.withLock {
        val c = dao.get(coffeeId) ?: return@withLock null
        if (c.state != CoffeeState.EXTRACTING) return@withLock null
        if ((c.nextAttemptAt ?: 0L) > clock.now()) return@withLock null
        run(coffeeId, mode)
    }

    /** The earliest parked retry among [ids], so the scheduler knows when to look again. */
    suspend fun nextDueAt(ids: List<String>): Long? =
        ids.mapNotNull { dao.get(it)?.takeIf { c -> c.state == CoffeeState.EXTRACTING }?.nextAttemptAt }.minOrNull()

    /**
     * Reads [coffeeId] once through the tiers, in order, and writes the result. Callers outside this
     * class go through [runIfDue], which holds the queue; tests call this directly.
     */
    suspend fun run(coffeeId: String, mode: RunMode = RunMode.FOREGROUND): ExtractionOutcome {
        // Under the lock a restore holds while it replaces the library, and before the row is read.
        startedIn[coffeeId] = photos.captureLock.withLock { photos.generation }
        val coffee = dao.get(coffeeId) ?: return ExtractionOutcome(ExtractionSource.NONE, null, "missing")
        val photoRows = dao.photos(coffeeId)
        if (photoRows.isEmpty()) {
            return finish(coffeeId, ExtractionSource.NONE, null, "", "no photos", ExtractionTrail(), baseline = coffee)
        }

        // 1. OCR every photo at full stored resolution.
        val pages = ocrAllPages(photoRows)
        val ocrText = pages.filter { it.text.isNotBlank() }.joinToString("\n\n") { "--- ${it.label} ---\n${it.text}" }.trim()
        // Out of attempts but still EXTRACTING: the last one was counted up front and the process
        // died before it finished. No model is asked again; the row finishes with the guess.
        if (coffee.extractionAttempts >= MAX_ATTEMPTS) {
            val why = "gave up after ${coffee.extractionAttempts} attempts"
            return fallback(coffeeId, pages, ocrText, why, ExtractionTrail(), baseline = coffee)
        }
        var imageCache: List<Bitmap>? = null
        // Every model tier gets the first MAX_MODEL_IMAGES photos in the order they were added (the
        // first two default to front and back, but a role can be changed before saving), decoded
        // once on first use; the OCR text above still covers every photo. A coffee can hold any number
        // of photos, so the bound keeps the decoded set and the cloud request from growing with it.
        suspend fun images(): List<Bitmap> =
            imageCache ?: photoRows.take(MAX_MODEL_IMAGES).mapNotNull { photos.load(it.original, 1024) }.also { imageCache = it }

        // 2. Nano with the images + OCR text.
        val trail = ExtractionTrail(mapOf(Tier.NANO to TierOutcome.SKIPPED))
        val afterNano = when (val step = nanoStep(coffeeId, ::images, pages, ocrText, mode)) {
            is NanoStep.Read -> {
                val note = "nano:${nano.modelName()}"
                val read = trail.with(Tier.NANO, TierOutcome.READ)
                // A thin reading (neither roaster nor coffee) gets a second opinion from the on-device
                // reader when it is downloaded; the reading with more fields is kept.
                if (step.facts.isThin && localReady()) {
                    val thin = ModelStep.Next(trail.with(Tier.NANO, TierOutcome.EMPTY), "nano read too little (${step.facts.fieldCount} fields)")
                    val second = localStep(coffeeId, ::images, pages, thin)
                    if (second is ModelStep.Read && second.facts.fieldCount > step.facts.fieldCount) {
                        val localNote = "local:${local.modelName()}; after ${second.notes}"
                        return finish(coffeeId, ExtractionSource.LOCAL, second.facts, ocrText, localNote, second.trail, baseline = coffee)
                    }
                }
                return finish(coffeeId, ExtractionSource.NANO, step.facts, ocrText, note, read, baseline = coffee)
            }
            is NanoStep.Stop -> return step.outcome
            is NanoStep.Fall -> ModelStep.Next(trail.with(Tier.NANO, step.outcome), step.why)
        }

        // 3. The on-device reader, if this device has it.
        val afterLocal = when (val step = localStep(coffeeId, ::images, pages, afterNano)) {
            is ModelStep.Read -> {
                val note = "local:${local.modelName()}; after ${step.notes}"
                return finish(coffeeId, ExtractionSource.LOCAL, step.facts, ocrText, note, step.trail, baseline = coffee)
            }
            is ModelStep.Next -> step
        }

        // 4. The user's own Gemini key, if any: same prompt, same parser, a real network call.
        val afterCloud = when (val step = cloudStep(::images, pages, afterLocal)) {
            is ModelStep.Read -> {
                val note = "cloud:${cloud.modelName()}; after ${step.notes}"
                return finish(coffeeId, ExtractionSource.CLOUD, step.facts, ocrText, note, step.trail, baseline = coffee)
            }
            is ModelStep.Next -> step
        }

        // 5. Nothing read the bag: the rule-based guess over the OCR text.
        Log.w(TAG, "falling back: ${afterCloud.notes}")
        return fallback(coffeeId, pages, ocrText, afterCloud.notes, afterCloud.trail, baseline = coffee)
    }

    /** OCR text of every photo, each bounded by a timeout so one stuck recognition cannot hold later bags. */
    private suspend fun ocrAllPages(photoRows: List<CoffeePhotoEntity>): List<OcrPage> = photoRows.mapIndexed { i, p ->
        val bmp = photos.load(p.original, 2048)
        val text = if (bmp == null) "" else {
            runCatchingCancellable { withTimeoutOrNull(timeouts.ocrMs) { ocr.recognize(bmp) } }.getOrNull().orEmpty()
        }
        OcrPage(label = "photo ${i + 1} (${p.role.name.lowercase()})", isSeller = p.role == PhotoRole.SELLER, text = text.trim())
    }

    /** What a model tier after Nano left: a reading, or the trail and notes to hand to the next tier. */
    private sealed interface ModelStep {
        data class Read(val facts: BagFacts, val trail: ExtractionTrail, val notes: String) : ModelStep
        data class Next(val trail: ExtractionTrail, val notes: String) : ModelStep
    }

    /** The on-device reader, when it is downloaded and this device can run it. */
    private suspend fun localStep(
        coffeeId: String, loadImages: suspend () -> List<Bitmap>, pages: List<OcrPage>, before: ModelStep.Next,
    ): ModelStep {
        var trail = before.trail
        var notes = before.notes
        when (runCatchingCancellable { local.availability() }.getOrDefault(LocalAvailability.UNSUPPORTED)) {
            LocalAvailability.READY -> {
                countAttemptUpFront(coffeeId)
                val attempt = try {
                    withTimeout(timeouts.localMs) {
                        modelAttempt("local", loadImages(), pages) { imgs, prompt -> local.generate(imgs, prompt) }
                    }
                } catch (e: TimeoutCancellationException) {
                    notes += "; local timed out"; null.also { trail = trail.with(Tier.LOCAL, TierOutcome.FAILED) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    notes += "; local ran out of memory"; null.also { trail = trail.with(Tier.LOCAL, TierOutcome.FAILED) }
                } catch (e: Exception) {
                    Log.w(TAG, "local failed", e)
                    notes += "; local threw ${e::class.simpleName}: ${e.message?.take(120)}"
                    null.also { trail = trail.with(Tier.LOCAL, TierOutcome.FAILED) }
                }
                if (attempt != null && !attempt.isEmpty) return ModelStep.Read(attempt, trail.with(Tier.LOCAL, TierOutcome.READ), notes)
                if (trail[Tier.LOCAL] == null) {
                    trail = trail.with(Tier.LOCAL, if (attempt != null) TierOutcome.EMPTY else TierOutcome.FAILED)
                    notes += if (attempt != null) "; local returned no fields" else "; local unparseable twice"
                }
            }
            LocalAvailability.NOT_DOWNLOADED -> trail = trail.with(Tier.LOCAL, TierOutcome.ABSENT)
            LocalAvailability.UNSUPPORTED -> trail = trail.with(Tier.LOCAL, TierOutcome.UNSUPPORTED)
        }
        return ModelStep.Next(trail, notes)
    }

    /** The cloud model, only when the user has saved their own Gemini key. */
    private suspend fun cloudStep(loadImages: suspend () -> List<Bitmap>, pages: List<OcrPage>, before: ModelStep.Next): ModelStep {
        if (!runCatchingCancellable { cloud.available() }.getOrDefault(false)) {
            return ModelStep.Next(before.trail.with(Tier.CLOUD, TierOutcome.OFF), before.notes)
        }
        var trail = before.trail
        var notes = before.notes
        val attempt = try {
            withTimeout(timeouts.cloudMs) { modelAttempt("cloud", loadImages(), pages) { imgs, prompt -> cloud.generate(imgs, prompt) } }
        } catch (e: TimeoutCancellationException) {
            notes += "; cloud timed out"; null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cloud failed: ${e::class.simpleName}")
            // The safe failure kind (key, model, limit, network...), never the exception text. The
            // trail keeps it too, so the card can say what to fix.
            val kind = CloudFailure.of(e)
            notes += "; cloud threw ${e::class.simpleName} (${kind.name.lowercase()})"
            // An unknown model is named on the card, so the person can spot a typo.
            val model = if (kind == CloudFailure.MODEL) runCatchingCancellable { cloud.modelName() }.getOrNull() else null
            trail = trail.copy(cloudFailure = kind, cloudModel = model)
            null
        }
        if (attempt != null && !attempt.isEmpty) return ModelStep.Read(attempt, trail.with(Tier.CLOUD, TierOutcome.READ), notes)
        trail = trail.with(Tier.CLOUD, if (attempt != null) TierOutcome.EMPTY else TierOutcome.FAILED)
        if (attempt != null) notes += "; cloud returned no fields"
        return ModelStep.Next(trail, notes)
    }

    private sealed interface NanoStep {
        data class Read(val facts: BagFacts) : NanoStep
        /** The run ends here: the row was parked for a retry, or left for the foreground. */
        data class Stop(val outcome: ExtractionOutcome) : NanoStep
        data class Fall(val outcome: TierOutcome, val why: String) : NanoStep
    }

    private suspend fun nanoStep(
        coffeeId: String, loadImages: suspend () -> List<Bitmap>, pages: List<OcrPage>, ocrText: String, mode: RunMode,
    ): NanoStep {
        // The person chose the on-device reader in Settings: Nano is not asked while the reader is ready.
        if (nanoHealth.readerPreferred() && localReady()) {
            return NanoStep.Fall(TierOutcome.SKIPPED, "nano skipped (reader chosen in Settings)")
        }
        val status: NanoStatus? = try {
            withTimeoutOrNull(timeouts.statusMs) { nano.status() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "nano status failed", e); NanoStatus.UNAVAILABLE
        }
        if (status == NanoStatus.UNAVAILABLE) return NanoStep.Fall(TierOutcome.UNSUPPORTED, "nano status=UNAVAILABLE")
        if (mode == RunMode.BACKGROUND) {
            // AICore refuses background callers. Only where Nano has been failing on this device AND
            // the on-device reader is ready to take over does the read go ahead without Nano;
            // otherwise the row waits for the foreground, where Nano gets its chance, and no attempt
            // is counted.
            return if (nanoHealth.nanoFailing() && localReady()) {
                NanoStep.Fall(TierOutcome.SKIPPED, "nano skipped in the background (failing on this device)")
            } else NanoStep.Stop(ExtractionOutcome(ExtractionSource.NONE, null, "deferred to the foreground"))
        }
        if (status == null) return park(coffeeId, pages, ocrText, "nano status timed out", delayed = true)
        if (status == NanoStatus.DOWNLOADABLE) {
            // The model (or an adapter) is on its way, or the library says so even when it is ready
            // (measured on a dev device, where prepare() returns at once). Show the first-download
            // state only once the wait is real, and bound the wait.
            val ready = try {
                coroutineScope {
                    val badge = launch { if (timeouts.preparingBadgeMs > 0) delay(timeouts.preparingBadgeMs); markPreparing(coffeeId) }
                    try {
                        withTimeoutOrNull(timeouts.prepareMs) {
                            if (timeouts.preparingBadgeMs == 0L) badge.join()
                            nano.prepare()
                            true
                        } ?: false
                    } finally { badge.cancel() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "nano prepare failed", e); false
            }
            if (!ready) return park(coffeeId, pages, ocrText, "model not ready", delayed = true)
        }
        val images = loadImages()
        val attempt = try {
            withTimeout(timeouts.nanoMs) { modelAttempt("nano", images, pages) { imgs, prompt -> nano.generate(imgs, prompt) } }
        } catch (e: TimeoutCancellationException) {
            nanoHealth.recordNano(ok = false, reason = "timed out")
            return NanoStep.Fall(TierOutcome.FAILED, "nano timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransientExtractionException) {
            return park(coffeeId, pages, ocrText, "transient: ${e.message}", delayed = false)
        } catch (e: Exception) {
            Log.w(TAG, "nano failed", e)
            nanoHealth.recordNano(ok = false, reason = "${e::class.simpleName}: ${e.message?.take(120)}")
            return NanoStep.Fall(TierOutcome.FAILED, "nano threw ${e::class.simpleName}: ${e.message?.take(160)}")
        }
        if (attempt != null && !attempt.isEmpty) {
            nanoHealth.recordNano(ok = true)
            return NanoStep.Read(attempt)
        }
        if (attempt != null) {
            return NanoStep.Fall(TierOutcome.EMPTY, "nano returned no fields (${images.size} images, ${ocrText.length} ocr chars)")
        }
        // A bag Nano could not put into JSON says nothing about the device: the flag is not set.
        return NanoStep.Fall(TierOutcome.FAILED, "nano unparseable twice (${images.size} images, ${ocrText.length} ocr chars)")
    }

    private suspend fun localReady(): Boolean =
        runCatchingCancellable { local.availability() }.getOrNull() == LocalAvailability.READY

    /** Shows "getting the reader ready" on the card while Nano's model downloads. */
    private suspend fun markPreparing(coffeeId: String) {
        db.withTransaction {
            dao.get(coffeeId)?.takeIf { it.state == CoffeeState.EXTRACTING }?.let {
                val trail = ExtractionTrail.decode(it.extractionTrail).with(Tier.NANO, TierOutcome.PREPARING)
                dao.update(it.copy(extractionTrail = trail.encode()))
            }
        }
    }

    /**
     * Nano could not answer yet, for a reason that may clear. Count it and leave the row EXTRACTING
     * (a download waits 3, then 5 minutes; a background block waits for the next foreground). The
     * last allowed attempt falls through to the other tiers instead, so a row never spins forever.
     */
    private suspend fun park(coffeeId: String, pages: List<OcrPage>, ocrText: String, note: String, delayed: Boolean): NanoStep {
        val attempts = (dao.get(coffeeId)?.extractionAttempts ?: 0) + 1
        if (attempts >= MAX_ATTEMPTS) {
            Log.w(TAG, "nano: giving up on $coffeeId after $attempts attempts: $note")
            nanoHealth.recordNano(ok = false, reason = note)
            return NanoStep.Fall(if (delayed) TierOutcome.NOT_READY else TierOutcome.FAILED, "nano gave up after $attempts attempts: $note")
        }
        val at = if (delayed) clock.now() + RETRY_DELAYS_MS[(attempts - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)] else null
        db.withTransaction {
            dao.get(coffeeId)?.let {
                val trail = ExtractionTrail.decode(it.extractionTrail)
                    .let { t -> if (delayed) t.with(Tier.NANO, TierOutcome.PREPARING) else t }
                dao.update(
                    it.copy(extractionAttempts = attempts, extractionMeta = note, nextAttemptAt = at, extractionTrail = trail.encode()),
                )
            }
        }
        return NanoStep.Stop(ExtractionOutcome(ExtractionSource.NONE, null, note))
    }

    /**
     * A failure the scheduler caught (an unexpected error around a run): count it and leave the row
     * EXTRACTING, unless this was the last allowed attempt, in which case finish with the OCR guess
     * so the row never spins forever.
     */
    suspend fun recordFailure(coffeeId: String, ocrText: String?, note: String): ExtractionOutcome {
        val alreadyCounted = countedUpFront.remove(coffeeId)
        val attempts = (dao.get(coffeeId)?.extractionAttempts ?: 0) + if (alreadyCounted) 0 else 1
        if (attempts >= MAX_ATTEMPTS) {
            Log.w(TAG, "giving up on $coffeeId after $attempts attempts: $note")
            return fallback(coffeeId, null, ocrText ?: "", "gave up after $attempts attempts; $note", ExtractionTrail())
        }
        // A retry time, not null: the scheduler only wakes for rows with one (or for a change in
        // which rows are being read), so a null left this row spinning until something else moved.
        val retryAt = clock.now() + FAILURE_RETRY_MS * attempts
        db.withTransaction {
            dao.get(coffeeId)?.let { dao.update(it.copy(extractionAttempts = attempts, extractionMeta = note, nextAttemptAt = retryAt)) }
        }
        return ExtractionOutcome(ExtractionSource.NONE, null, note)
    }

    /** Counts this read's attempt before a call that can end the process (see [countedUpFront]). */
    private suspend fun countAttemptUpFront(coffeeId: String) {
        if (!countedUpFront.add(coffeeId)) return
        db.withTransaction { dao.get(coffeeId)?.let { dao.update(it.copy(extractionAttempts = it.extractionAttempts + 1)) } }
    }

    /** The last tier: the rule-based guess over the OCR text, filling blank fields only. */
    private suspend fun fallback(
        coffeeId: String, pages: List<OcrPage>?, ocrText: String, why: String, trail: ExtractionTrail, baseline: CoffeeEntity? = null,
    ): ExtractionOutcome {
        if (ocrText.isBlank()) {
            val empty = trail.with(Tier.GUESS, TierOutcome.EMPTY)
            return finish(coffeeId, ExtractionSource.NONE, null, "", "nothing readable; $why", empty, baseline)
        }
        val guess = if (pages != null) OcrGuess.guess(pages) else OcrGuess.guess(ocrText)
        val facts = guess.takeIf { !it.isEmpty }
        return finish(coffeeId, ExtractionSource.OCR, facts, ocrText, "ocr-guess; $why", trail, baseline, fillBlanksOnly = true)
    }

    /** Ask a model tier, parse, and ask once more with a stricter instruction if the answer was not JSON. */
    private suspend fun modelAttempt(
        tier: String, images: List<Bitmap>, pages: List<OcrPage>, generate: suspend (List<Bitmap>, String) -> String,
    ): BagFacts? {
        val first = generate(images, ExtractionPrompt.build(pages))
        logAnswer(tier, 1, first)
        BagFactsParser.parse(first)?.let { return it }
        val second = generate(images, ExtractionPrompt.build(pages, strictRetry = true))
        logAnswer(tier, 2, second)
        return BagFactsParser.parse(second)
    }

    /** Model output can echo bag and seller text; only debug builds log its content. */
    private fun logAnswer(tier: String, n: Int, answer: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, "$tier answer $n (${answer.length} chars): ${answer.take(300).replace('\n', ' ')}")
        else Log.i(TAG, "$tier answer $n (${answer.length} chars)")
    }

    companion object {
        private const val TAG = "Extraction"
        private val MODEL_SOURCES = setOf(ExtractionSource.NANO, ExtractionSource.LOCAL, ExtractionSource.CLOUD)
        const val MAX_ATTEMPTS = 3
        /** Parked retries while Nano's model downloads: +3 minutes, then +5; the third miss falls through. */
        val RETRY_DELAYS_MS = longArrayOf(3 * 60_000L, 5 * 60_000L)
        /** After an unexpected error the row is tried again after this times its attempt count. */
        const val FAILURE_RETRY_MS = 60_000L
        /** Images a model tier is given; the on-device reader's engine takes at most this many (LiteRtLocalReader). */
        const val MAX_MODEL_IMAGES = 4
    }

    /**
     * [baseline] is the row as it was when the run began (null when a caller has no snapshot, e.g. the
     * scheduler's failure path, which never carries facts). An extracted field named in the fresh row's
     * [CoffeeEntity.userEdited] list, or whose value moved since the snapshot, was edited by hand: it
     * keeps the user's value and the tier's suggestion for it is dropped. Fields untouched fill from
     * [facts] as usual. [fillBlanksOnly] (the OCR guess) fills only empty fields and never touches a
     * coffee a model already read.
     */
    private suspend fun finish(
        coffeeId: String, source: ExtractionSource, facts: BagFacts?, ocrText: String, note: String, trail: ExtractionTrail,
        baseline: CoffeeEntity? = null, fillBlanksOnly: Boolean = false,
    ): ExtractionOutcome {
        var f = facts ?: BagFacts()
        var finalNote = note
        var finalFacts = facts
        db.withTransaction {
            // A restore since the run began replaced the library, possibly with this same id: the
            // result describes the replaced row. The restore bumps the generation before its own
            // transaction, and Room runs one write transaction at a time, so this cannot miss it.
            if (startedIn.remove(coffeeId)?.let { it != photos.generation } == true) return@withTransaction
            // Fresh read: the row may have been rated, reminded, edited or deleted since this run started.
            val coffee = dao.get(coffeeId) ?: return@withTransaction
            // A guess never replaces a model's reading: a failed reread of a model-read coffee keeps it whole.
            val modelRead = coffee.extractionSource in MODEL_SOURCES
            if (fillBlanksOnly && modelRead) { f = BagFacts(); finalFacts = null }
            val userEdited = mutableListOf<String>()
            var filled = 0
            // Fields whose final value is this read's suggestion (for a guess: how many fields are a guess).
            var matching = 0
            fun blank(v: Any?) = v == null || (v is String && v.isBlank()) || (v is List<*> && v.isEmpty())
            fun <T> merge(name: String, current: T, base: T?, suggested: T?): T {
                if (name in coffee.userEdited || (baseline != null && current != base)) {
                    if (suggested != null) userEdited += name
                    return current
                }
                if (suggested == null) return current
                if (fillBlanksOnly && !blank(current)) { if (current == suggested) matching++; return current }
                if (suggested != current) filled++
                matching++
                return suggested
            }
            val updated = coffee.copy(
                // A re-read must not un-rate a coffee.
                state = if (coffee.overall != null) CoffeeState.RATED else CoffeeState.LOGGED,
                roaster = merge("roaster", coffee.roaster, baseline?.roaster, TextCase.title(f.roaster)),
                coffeeName = merge("coffeeName", coffee.coffeeName, baseline?.coffeeName, TextCase.title(f.coffeeName)),
                originCountry = merge("originCountry", coffee.originCountry, baseline?.originCountry, TextCase.title(f.originCountry)),
                region = merge("region", coffee.region, baseline?.region, TextCase.title(f.region)),
                producer = merge("producer", coffee.producer, baseline?.producer, TextCase.title(f.producer)),
                process = merge("process", coffee.process, baseline?.process, TextCase.sentence(f.process)),
                varietal = merge(
                    "varietal", coffee.varietal, baseline?.varietal, TextCase.titleList(f.varietal).takeIf { it.isNotEmpty() },
                ),
                elevationM = merge("elevationM", coffee.elevationM, baseline?.elevationM, f.elevationM),
                roastLevel = merge("roastLevel", coffee.roastLevel, baseline?.roastLevel, TextCase.sentence(f.roastLevel)),
                roastDate = merge("roastDate", coffee.roastDate, baseline?.roastDate, f.roastDate),
                bagSizeG = merge("bagSizeG", coffee.bagSizeG, baseline?.bagSizeG, f.bagSizeG),
                tastingNotes = merge(
                    "tastingNotes", coffee.tastingNotes, baseline?.tastingNotes,
                    TextCase.titleList(f.tastingNotes).takeIf { it.isNotEmpty() },
                ),
                bagDescription = merge(
                    "bagDescription", coffee.bagDescription, baseline?.bagDescription, TextCase.sentence(f.bagDescription),
                ),
                sellerDescription = merge(
                    "sellerDescription", coffee.sellerDescription, baseline?.sellerDescription, TextCase.sentence(f.sellerDescription),
                ),
                ocrText = ocrText.ifBlank { null } ?: coffee.ocrText,
                // Provenance names the tier that produced the retained fields: a failed re-read keeps it.
                extractionSource = when {
                    modelRead && source !in MODEL_SOURCES -> coffee.extractionSource
                    // Every suggestion was for a field the user edited: none of this model's reading is kept.
                    source in MODEL_SOURCES && matching == 0 -> coffee.extractionSource
                    finalFacts == null && coffee.extractionSource != ExtractionSource.NONE && source == ExtractionSource.NONE ->
                        coffee.extractionSource
                    else -> source
                },
                // The note lists fields the user authored, so the tier is not credited with them.
                extractionMeta = if (userEdited.isEmpty()) note else "$note; user-edited: ${userEdited.joinToString(", ")}",
                extractionAttempts = coffee.extractionAttempts + if (countedUpFront.remove(coffeeId)) 0 else 1,
                nextAttemptAt = null,
                extractionTrail = null,
                updatedAt = System.currentTimeMillis(),
            )
            val isGuess = source == ExtractionSource.OCR
            val guessOutcome = if (matching > 0) TierOutcome.READ else TierOutcome.EMPTY
            val t = if (isGuess) trail.copy(guessedFields = matching).with(Tier.GUESS, guessOutcome) else trail
            val meta = updated.extractionMeta!!
            val withNote = if (isGuess) meta.replaceFirst("ocr-guess;", "ocr-guess ($filled fields);") else meta
            finalNote = withNote
            dao.update(updated.copy(extractionMeta = withNote, extractionTrail = t.encode().ifEmpty { null }))
        }
        return ExtractionOutcome(source, finalFacts, finalNote)
    }
}
