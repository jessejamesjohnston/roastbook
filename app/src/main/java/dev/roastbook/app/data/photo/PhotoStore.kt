package dev.roastbook.app.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns everything under filesDir/photos/{coffeeId}/. Originals are stored at most 2048 px on the
 * long edge (enough to re-run OCR/extraction later); the hero also gets a 1024 px square and a
 * 256 px thumb. Paths handed out are relative to filesDir so a backup zip is portable.
 */
@Singleton
open class PhotoStore @Inject constructor(@ApplicationContext private val context: Context) {
    val root: File get() = File(context.filesDir, ROOT)

    /**
     * Held while a backup captures the database and lists the photo tree, while a coffee is
     * deleted, while a restore swaps the library in, and while a hero crop writes its result: an
     * archive then never holds a coffee whose photos were deleted between the two, and a crop never
     * writes over a coffee that was deleted or restored while it ran. Order: BackupManager's own
     * lock first, then this one; nothing takes this one twice.
     */
    val captureLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Which library the photo tree and rows belong to. A restore bumps it, holding [captureLock],
     * before it swaps anything; work that read the library earlier (a hero crop) compares it under
     * the same lock before writing, so it never writes into the library that replaced its own.
     */
    @Volatile var generation: Long = 0
        private set

    /** Call only while holding [captureLock]. */
    fun newGeneration() { generation++ }
    val staging: File get() = File(context.cacheDir, "staging").apply { mkdirs() }

    /**
     * The file a stored path names. Only paths of the shape the app writes resolve; anything else
     * (a restored row with "..", an absolute path, another directory) names a file that does not
     * exist, so it can't be read, copied into a backup, or overwritten.
     */
    fun file(relative: String): File = if (isPhotoPath(relative)) File(context.filesDir, relative) else File(root, INVALID)
    /**
     * The coffee's photo directory. Ids become file names here, and a row adopted from a restored
     * snapshot is not trusted to hold the UUIDs the app writes, so anything but a plain name throws
     * before a directory is made.
     */
    fun dir(coffeeId: String): File {
        require(SAFE_NAME.matches(coffeeId)) { "not a plain coffee id" }
        return File(root, coffeeId).apply { mkdirs() }
    }

    /** Copies a captured/picked image into the coffee's directory, downscaled to 2048 px, EXIF-upright. */
    suspend fun importOriginal(coffeeId: String, photoId: String, source: File): ImportedPhoto = withContext(Dispatchers.IO) {
        val takenAt = exifTakenAt(source) ?: source.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        val bitmap = decodeUpright(source, 2048) ?: error("Could not decode ${source.name}")
        require(SAFE_NAME.matches(photoId)) { "not a plain photo id" }
        val dest = File(dir(coffeeId), "$photoId.jpg")
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        ImportedPhoto(relative(dest), takenAt, bitmap.width, bitmap.height)
    }

    /** Writes the hero square (1024) and thumb (256) for a photo from a crop rect in original pixels. */
    suspend fun writeHeroRenditions(coffeeId: String, photoId: String, original: Bitmap, crop: Rect): Pair<String, String> =
        withContext(Dispatchers.IO) {
            require(SAFE_NAME.matches(photoId)) { "not a plain photo id" }
            val safe = Rect(
                crop.left.coerceIn(0, original.width - 1),
                crop.top.coerceIn(0, original.height - 1),
                crop.right.coerceIn(1, original.width),
                crop.bottom.coerceIn(1, original.height),
            )
            val cropped = Bitmap.createBitmap(original, safe.left, safe.top, safe.width(), safe.height())
            val square = cropped.scale(1024, 1024)
            val thumb = cropped.scale(256, 256)
            val squareFile = File(dir(coffeeId), "$photoId.square.jpg")
            val thumbFile = File(dir(coffeeId), "$photoId.thumb.jpg")
            squareFile.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            thumbFile.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            relative(squareFile) to relative(thumbFile)
        }

    open suspend fun load(relative: String, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) { decodeUpright(file(relative), maxEdge) }

    /** Deletes one coffee's photo directory; an id that is not a plain directory name deletes nothing. */
    suspend fun deleteCoffee(coffeeId: String) = withContext(Dispatchers.IO) {
        if (!SAFE_NAME.matches(coffeeId)) return@withContext false
        File(root, coffeeId).deleteRecursively()
    }

    /** Photo directories with no coffee row in [liveIds]. The caller re-checks each one before deleting. */
    suspend fun orphanDirs(liveIds: Set<String>): List<File> = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.isDirectory && it.name !in liveIds } ?: emptyList()
    }

    fun newStagingFile(): File = File(staging, "capture-${System.currentTimeMillis()}-${(0..9999).random()}.jpg")

    suspend fun stageFromStream(open: () -> InputStream?): File? {
        val dest = newStagingFile()
        // The catch sits outside withContext: a caller canceled while the blocking copy ran gets
        // the cancellation as withContext returns, after the copy finished, and the file is
        // never handed back to anyone who would save or delete it.
        try {
            return withContext(Dispatchers.IO) {
                open()?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return@withContext null
                dest
            }
        } catch (e: Throwable) {
            dest.delete() // no half-copied (or finished but abandoned) photo left in the cache
            throw e
        }
    }

    private fun relative(f: File): String = f.relativeTo(context.filesDir).path

    private fun exifTakenAt(f: File): Long? = runCatching {
        ExifInterface(f).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { s ->
            java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(s)?.time
        }
    }.getOrNull()

    companion object {
        /** The photo tree's directory name under filesDir; the restore journal needs it too. */
        const val ROOT = "photos"
        private const val INVALID = ".invalid-path"
        private val SAFE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")

        /** photos/<dir>/<file>.jpg with plain names: what [importOriginal] and the renditions write. */
        fun isPhotoPath(relative: String): Boolean {
            val parts = relative.split('/')
            return parts.size == 3 && parts[0] == ROOT && SAFE_NAME.matches(parts[1]) && SAFE_NAME.matches(parts[2]) &&
                parts[2].endsWith(".jpg") && parts.none { it == ".." || it == "." }
        }

        /** Decode with inSampleSize so the long edge is at most [maxEdge], then apply EXIF rotation. */
        fun decodeUpright(file: File, maxEdge: Int): Bitmap? {
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            var bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val longEdge = maxOf(bmp.width, bmp.height)
            if (longEdge > maxEdge) {
                val scale = maxEdge.toFloat() / longEdge
                bmp = bmp.scale((bmp.width * scale).toInt(), (bmp.height * scale).toInt())
            }
            // All eight EXIF orientations: the four rotations and the four mirrored variants
            // (gallery imports can carry any of them; the camera path is always upright or rotated).
            val exif = runCatching { ExifInterface(file) }.getOrNull()
            val degrees = exif?.rotationDegrees ?: 0
            val flipped = exif?.isFlipped ?: false
            if (degrees == 0 && !flipped) return bmp
            val m = Matrix().apply {
                if (flipped) postScale(-1f, 1f)
                if (degrees != 0) postRotate(degrees.toFloat())
            }
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
    }
}

/**
 * A stored original: its path relative to filesDir, when it was taken (EXIF, else the file time,
 * else now) and its stored pixel size.
 */
data class ImportedPhoto(val relativePath: String, val takenAt: Long, val width: Int, val height: Int)
