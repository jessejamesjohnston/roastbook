package dev.roastbook.app.extraction

import android.graphics.Bitmap
import dev.roastbook.app.cloud.CloudTier
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.testutil.TestGraph
import java.io.File

/** Tier fakes shared by the scheduler, worker and branch tests (ExtractorTest keeps its own). */
internal class ScriptedNano(
    var status: NanoStatus = NanoStatus.AVAILABLE,
    val answers: MutableList<suspend () -> String> = mutableListOf(),
    var onStatus: (suspend () -> NanoStatus)? = null,
    var onPrepare: suspend () -> Unit = {},
) : NanoTier {
    var calls = 0
    override suspend fun status(): NanoStatus = onStatus?.invoke() ?: status
    override suspend fun prepare() = onPrepare()
    override suspend fun generate(images: List<Bitmap>, prompt: String): String { calls++; return answers.removeAt(0)() }
    override fun modelName() = "scripted-nano"
}

internal class TextOcr(private val text: String = "") : OcrTier {
    override suspend fun recognize(image: Bitmap) = text
}

internal object OffCloud : CloudTier {
    override suspend fun available() = false
    override suspend fun generate(images: List<Bitmap>, prompt: String) = error("unused")
    override suspend fun modelName() = "none"
}

internal class ScriptedCloud(private val answer: suspend () -> String) : CloudTier {
    var calls = 0
    override suspend fun available() = true
    override suspend fun generate(images: List<Bitmap>, prompt: String): String { calls++; return answer() }
    override suspend fun modelName() = "scripted-cloud"
}

internal class ScriptedReader(
    var availability: LocalAvailability = LocalAvailability.UNSUPPORTED,
    private val answer: suspend () -> String = { error("unused") },
) : LocalTier {
    var calls = 0
    override suspend fun availability() = availability
    override suspend fun generate(images: List<Bitmap>, prompt: String): String { calls++; return answer() }
    override fun modelName() = "scripted-reader"
}

internal class MemoryHealth(var failing: Boolean = false) : NanoHealth {
    override suspend fun nanoFailing() = failing
    override suspend fun recordNano(ok: Boolean, reason: String?) { failing = !ok }
}

/** Short bounds: a fake that hangs costs a second, not minutes. */
internal val shortTimeouts = ExtractionTimeouts(statusMs = 1_000, preparingBadgeMs = 0, prepareMs = 1_000, nanoMs = 1_000, localMs = 1_000, cloudMs = 1_000, ocrMs = 1_000)

/** An EXTRACTING coffee with one front photo row (and its file, so the extractor finds it). */
internal suspend fun TestGraph.extractingCoffee(id: String, build: CoffeeEntity.() -> CoffeeEntity = { this }): CoffeeEntity {
    val c = insertCoffee(id, CoffeeState.EXTRACTING, build = build)
    File(photos.dir(id), "p-$id.jpg").outputStream().use {
        android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
    }
    dao.insertPhotos(listOf(CoffeePhotoEntity("p-$id", id, PhotoRole.FRONT, 0, "photos/$id/p-$id.jpg", takenAt = 1, addedAt = 1)))
    return c
}
