package dev.roastbook.app.extraction

import android.graphics.Bitmap

/** The structured facts a bag can carry. Every field is optional; the parser never invents. */
data class BagFacts(
    val roaster: String? = null,
    val coffeeName: String? = null,
    val originCountry: String? = null,
    val region: String? = null,
    val producer: String? = null,
    val process: String? = null,
    val varietal: List<String> = emptyList(),
    val elevationM: Int? = null,
    val roastLevel: String? = null,
    val roastDate: String? = null,
    val bagSizeG: Int? = null,
    val tastingNotes: List<String> = emptyList(),
    val bagDescription: String? = null,
    val sellerDescription: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(
            roaster, coffeeName, originCountry, region, producer, process, roastLevel, roastDate, bagDescription, sellerDescription,
        )
            .all { it.isNullOrBlank() } && varietal.isEmpty() && tastingNotes.isEmpty() && elevationM == null && bagSizeG == null

    /** How many fields hold something. */
    val fieldCount: Int
        get() = listOf(
            roaster, coffeeName, originCountry, region, producer, process, roastLevel, roastDate, bagDescription, sellerDescription,
        ).count { !it.isNullOrBlank() } + listOf(varietal, tastingNotes).count { it.isNotEmpty() } +
            listOf(elevationM, bagSizeG).count { it != null }

    /**
     * A reading that names neither the roaster nor the coffee. Nearly every bag prints at least one
     * of them, so a model that found neither most likely misread the label.
     */
    val isThin: Boolean get() = roaster.isNullOrBlank() && coffeeName.isNullOrBlank()
}

/**
 * What Gemini Nano can do on this device: AVAILABLE to run now, DOWNLOADABLE while the model is
 * absent or still downloading, UNAVAILABLE when the device does not support it.
 */
enum class NanoStatus { AVAILABLE, DOWNLOADABLE, UNAVAILABLE }

/** A model that can look at photos + text and answer. Gemini Nano in production, a fake in tests. */
interface NanoTier {
    suspend fun status(): NanoStatus
    /** Best-effort model download; returns when done or when the library gives up. */
    suspend fun prepare()
    suspend fun generate(images: List<Bitmap>, prompt: String): String
    fun modelName(): String
}

/** Text recognition for one photo. ML Kit in production, a fake in tests. */
interface OcrTier {
    suspend fun recognize(image: Bitmap): String
}

/** Named per-photo OCR text, so the prompt can say which photo is the seller's screenshot. */
data class OcrPage(val label: String, val isSeller: Boolean, val text: String)

/** Whether the downloadable on-device reader can run a read right now. */
enum class LocalAvailability {
    READY,
    /** The device qualifies but the model has not been downloaded (or failed its checksum). */
    NOT_DOWNLOADED,
    /** Too little memory or an unsupported CPU: the reader is never offered here. */
    UNSUPPORTED,
}

/**
 * The on-device reader: an open model the app downloads and runs in its own process (LiteRT-LM on
 * the CPU in production, a fake in tests). Because it is not a system service, it also runs while
 * the app is in the background.
 */
interface LocalTier {
    suspend fun availability(): LocalAvailability
    suspend fun generate(images: List<Bitmap>, prompt: String): String
    fun modelName(): String
}

/**
 * Device-scoped memory of how Gemini Nano has behaved here, and the person's choice of reader on
 * this device; kept out of every backup.
 */
interface NanoHealth {
    /** True once Nano has failed on this device, until it next succeeds. */
    suspend fun nanoFailing(): Boolean
    suspend fun recordNano(ok: Boolean, reason: String? = null)
    /** The person chose, in Settings, to have the on-device reader read bags instead of Nano. */
    suspend fun readerPreferred(): Boolean = false
}

/** Where a read runs. The system model (Nano) refuses background callers; the other tiers don't. */
enum class RunMode { FOREGROUND, BACKGROUND }

/** Wall clock, injectable so the retry schedule is testable. */
fun interface Clock { fun now(): Long }

/** Upper bounds on each tier call, so no single call can hold the read queue. */
data class ExtractionTimeouts(
    val statusMs: Long = 10_000L,
    /** A prepare() that returns within this is not a download; no "getting the reader ready" flash. */
    val preparingBadgeMs: Long = 1_500L,
    /** How long one read waits on Nano's download before parking the row (the download itself continues). */
    val prepareMs: Long = 60_000L,
    /**
     * Nano answers in 2-4 s on a Pixel phone, but one MediaTek tablet took 26 s for a 255-token
     * answer (about 10 tokens a second), so this bound is not far above a long, normal answer there.
     * A call still running after it is stuck or running away.
     */
    val nanoMs: Long = 90_000L,
    /** The on-device reader takes 20-35 s per bag on a flagship CPU (two calls if the first is not JSON); slower phones get room. */
    val localMs: Long = 6 * 60_000L,
    val cloudMs: Long = 120_000L,
    /** Text recognition of one photo takes a second or two; a call still running after this is stuck. */
    val ocrMs: Long = 30_000L,
)

/** Thrown by a tier when the failure is expected to clear (backgrounded, model still downloading). */
class TransientExtractionException(message: String) : Exception(message)
