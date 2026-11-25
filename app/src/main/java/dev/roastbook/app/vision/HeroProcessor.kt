package dev.roastbook.app.vision

import android.graphics.Bitmap
import android.util.Log
import dev.roastbook.app.data.db.CoffeeDao
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.data.photo.SquareExporter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/** Auto-crop the hero, write square + thumb, compute the row colors, store all of it. */
@Singleton
class HeroProcessor @Inject constructor(
    private val dao: CoffeeDao,
    private val photos: PhotoStore,
    private val cropper: SubjectCropper,
    private val palette: PaletteExtractor,
    private val exporter: SquareExporter,
) {
    /** Every non-canceled exit counts as an attempt, so a broken original is retried three times, not forever. */
    suspend fun process(coffeeId: String) {
        try {
            doProcess(coffeeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.bumpHeroAttempts(coffeeId)
            throw e
        }
    }

    private suspend fun giveUp(coffeeId: String, why: String) {
        Log.w(TAG, "hero $coffeeId: $why")
        dao.bumpHeroAttempts(coffeeId)
    }

    private suspend fun doProcess(coffeeId: String) {
        // Taken under the lock a restore holds while it replaces the library, so this crop knows
        // which library it read from; the rows below are read after it.
        val generation = photos.captureLock.withLock { photos.generation }
        val coffee = dao.get(coffeeId) ?: return
        val photo = dao.photos(coffeeId).firstOrNull { it.id == coffee.heroPhotoId } ?: return giveUp(coffeeId, "hero photo row missing")
        val bitmap = photos.load(photo.original, 1024) ?: return giveUp(coffeeId, "hero original unreadable")
        val t0 = System.currentTimeMillis()
        val crop = cropper.crop(bitmap)
        Log.i(TAG, "hero $coffeeId: segmented=${crop.segmented} square=${crop.square} " +
            "(${bitmap.width}x${bitmap.height}) in ${System.currentTimeMillis() - t0} ms")
        val original = photos.load(photo.original, 2048) ?: return giveUp(coffeeId, "hero original unreadable at 2048")
        val scale = original.width.toFloat() / bitmap.width
        val rect = android.graphics.Rect(
            (crop.square.left * scale).toInt(), (crop.square.top * scale).toInt(),
            (crop.square.right * scale).toInt(), (crop.square.bottom * scale).toInt(),
        )
        val subject: Bitmap = crop.foreground ?: Bitmap.createBitmap(
            bitmap, crop.square.left, crop.square.top, crop.square.width(), crop.square.height(),
        )
        val colors = palette.extract(subject)
        Log.i(TAG, "palette $coffeeId: fallback=${colors.fallback} tintLight=#%06x".format(colors.tintLight and 0xFFFFFF))

        // The crop took seconds. Write only if the coffee is still the one it read: not deleted, and
        // not replaced by a restore (which can bring back the very same ids, even the same rows).
        // Under the same lock as a delete and a restore's swap, so neither can land mid-write.
        val square = photos.captureLock.withLock {
            if (photos.generation != generation) return@withLock null
            val nowCoffee = dao.get(coffeeId) ?: return@withLock null
            val nowPhoto = dao.photos(coffeeId).firstOrNull { it.id == photo.id } ?: return@withLock null
            if (nowCoffee.heroPhotoId != photo.id || nowPhoto.original != photo.original) return@withLock null
            val (square, thumb) = photos.writeHeroRenditions(coffeeId, photo.id, original, rect)
            dao.updatePhoto(nowPhoto.copy(square = square, thumb = thumb))
            // Column-scoped: extraction, a rating or an edit may be writing the same row right now.
            dao.setHeroResult(coffeeId, colors.tintLight, colors.tintDark, colors.accentLight, colors.accentDark, colors.fallback, crop.segmented)
            square
        }
        // Not an attempt: the coffee this crop was for is gone or was replaced, and is not ours to count against.
        if (square == null) { Log.i(TAG, "hero $coffeeId: the coffee changed while cropping; result dropped"); return }
        exporter.maybeExport(photos.file(square), "roastbook-$coffeeId")
    }

    private companion object { const val TAG = "Hero" }
}
