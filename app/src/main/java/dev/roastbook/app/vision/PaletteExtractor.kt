package dev.roastbook.app.vision

import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import dev.roastbook.app.color.RowColors
import dev.roastbook.app.color.TintRule
import javax.inject.Inject
import javax.inject.Singleton

/** Turns the bag's subject pixels into its row colors, by way of [TintRule]. */
interface PaletteExtractor {
    fun extract(subject: Bitmap): RowColors
}

/**
 * Android Palette over the SUBJECT pixels only. A foreground bitmap from segmentation has
 * transparent background pixels, which Palette would count as black, so opaque pixels are packed
 * into a small scratch bitmap first.
 */
@Singleton
class AndroidPaletteExtractor @Inject constructor() : PaletteExtractor {
    override fun extract(subject: Bitmap): RowColors {
        val packed = packOpaque(subject, 128)
        val palette = Palette.from(packed).maximumColorCount(24).generate()
        return TintRule.compute(palette.vibrantSwatch?.rgb, palette.mutedSwatch?.rgb)
    }

    private fun packOpaque(src: Bitmap, side: Int): Bitmap {
        val scaled = src.scale(side, side)
        val pixels = IntArray(side * side)
        scaled.getPixels(pixels, 0, side, 0, 0, side, side)
        val opaque = pixels.filter { (it ushr 24) >= 128 }
        if (opaque.size < 64) return scaled
        val out = IntArray(side * side) { opaque[it % opaque.size] }
        return Bitmap.createBitmap(out, side, side, Bitmap.Config.ARGB_8888)
    }
}
