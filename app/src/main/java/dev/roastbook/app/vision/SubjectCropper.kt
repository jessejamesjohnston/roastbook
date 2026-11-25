package dev.roastbook.app.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Result of the auto-crop: a square rect in the bitmap's pixel space and, if segmentation ran, the foreground. */
data class CropResult(val square: Rect, val foreground: Bitmap?, val segmented: Boolean)

/**
 * Finds the bag in a photo and returns the square to crop around it. Never fails for want of a
 * model: when segmentation is unavailable or finds nothing, the result is a centered square.
 */
interface SubjectCropper {
    suspend fun crop(bitmap: Bitmap): CropResult
}

/**
 * ML Kit Subject Segmentation → confidence mask → bounding box → 8% pad → expand to a square
 * centered on the box → clamp to bounds. Falls back to a centered square when the model is not
 * available or finds nothing.
 */
@Singleton
class MlKitSubjectCropper @Inject constructor() : SubjectCropper {
    override suspend fun crop(bitmap: Bitmap): CropResult {
        val t0 = System.currentTimeMillis()
        android.util.Log.i("Hero", "segmenting ${bitmap.width}x${bitmap.height}")
        val mask = try {
            kotlinx.coroutines.withTimeout(20_000) { segment(bitmap) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            android.util.Log.w("Hero", "segmentation timed out after ${System.currentTimeMillis() - t0} ms")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // our coroutine was canceled (app backgrounded); never count it as a model failure
        } catch (e: Throwable) {
            android.util.Log.w("Hero", "segmentation unavailable after ${System.currentTimeMillis() - t0} ms: $e")
            null
        }
        android.util.Log.i("Hero", "segmentation ${if (mask == null) "FAILED" else "ok"} in ${System.currentTimeMillis() - t0} ms")
        val box = mask?.let { boundingBox(it, bitmap.width, bitmap.height) }
        return if (box == null) CropResult(centerSquare(bitmap.width, bitmap.height), null, false)
        else CropResult(squareAround(box, bitmap.width, bitmap.height), mask.foreground, true)
    }

    private class Mask(val confidence: FloatArray, val foreground: Bitmap?)

    private suspend fun segment(bitmap: Bitmap): Mask = suspendCancellableCoroutine { cont ->
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        // A timed-out or canceled call releases the segmenter too (close() is idempotent and
        // cancels the task in flight), as the text recognizer does.
        cont.invokeOnCancellation { segmenter.close() }
        segmenter.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val buf = result.foregroundConfidenceMask
                if (buf == null) cont.resumeWithException(IllegalStateException("no mask"))
                else {
                    val arr = FloatArray(buf.remaining()); buf.get(arr)
                    cont.resume(Mask(arr, result.foregroundBitmap))
                }
            }
            .addOnFailureListener { cont.resumeWithException(it) }
            .addOnCompleteListener { segmenter.close() }
    }

    /**
     * Bounding box of the DENSE part of the mask: per-row and per-column coverage is computed and
     * the box is trimmed to where coverage is at least 35% of the peak. A hand holding the bag
     * adds thin, sparse protrusions (fingers, an arm along one edge) that would otherwise drag the
     * square off-center; the bag itself is a solid block and survives the trim.
     */
    private fun boundingBox(mask: Mask, w: Int, h: Int): Rect? {
        val conf = mask.confidence
        if (conf.size != w * h) return null
        val rows = IntArray(h); val cols = IntArray(w); var count = 0
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) if (conf[base + x] >= 0.5f) { rows[y]++; cols[x]++; count++ }
        }
        if (count < w * h * 0.04) return null
        fun denseRange(a: IntArray): IntRange? {
            val peak = a.max(); if (peak == 0) return null
            val floor = peak * 0.45
            var lo = 0; while (lo < a.size && a[lo] < floor) lo++
            var hi = a.size - 1; while (hi >= 0 && a[hi] < floor) hi--
            return if (hi < lo) null else lo..hi
        }
        val ys = denseRange(rows) ?: return null
        val xs = denseRange(cols) ?: return null
        return Rect(xs.first, ys.first, xs.last + 1, ys.last + 1)
    }

    companion object {
        fun centerSquare(w: Int, h: Int): Rect {
            val side = minOf(w, h)
            val left = (w - side) / 2
            val top = (h - side) / 2
            return Rect(left, top, left + side, top + side)
        }

        /**
         * Tight square on the subject: side = the box's SHORTER edge (plus 4%), centered on the box.
         * For a tall bag that trims the top and bottom edges and keeps the label/logo band; for a
         * near-square box it is the box. Background is never padded in.
         */
        fun squareAround(box: Rect, w: Int, h: Int): Rect {
            var side = (minOf(box.width(), box.height()) * 1.04f).toInt()
            side = side.coerceIn(minOf(w, h) / 4, minOf(w, h))
            val cx = box.centerX(); val cy = box.centerY()
            var left = cx - side / 2; var top = cy - side / 2
            left = left.coerceIn(0, w - side); top = top.coerceIn(0, h - side)
            return Rect(left, top, left + side, top + side)
        }
    }
}
