package dev.roastbook.app.color

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** OKLCH color: L in 0..1, C ≥ 0, H in degrees. */
data class Oklch(val l: Double, val c: Double, val h: Double)

/** sRGB ⇄ OKLab/OKLCH (Björn Ottosson's matrices). Enough for the tint rule; not a color library. */
object OklchConverter {
    private fun toLinear(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun toSrgb(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055

    fun fromArgb(argb: Int): Oklch {
        val r = toLinear(((argb shr 16) and 0xFF) / 255.0)
        val g = toLinear(((argb shr 8) and 0xFF) / 255.0)
        val b = toLinear((argb and 0xFF) / 255.0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val lab0 = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val labA = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val labB = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val c = sqrt(labA * labA + labB * labB)
        var h = Math.toDegrees(atan2(labB, labA))
        if (h < 0) h += 360.0
        return Oklch(lab0, c, h)
    }

    /** Converts back, clipping out-of-gamut channels (fine for the low-chroma tints we produce). */
    fun toArgb(color: Oklch): Int {
        val hr = Math.toRadians(color.h)
        val a = color.c * cos(hr)
        val bb = color.c * sin(hr)
        val l = (color.l + 0.3963377774 * a + 0.2158037573 * bb).pow(3)
        val m = (color.l - 0.1055613458 * a - 0.0638541728 * bb).pow(3)
        val s = (color.l - 0.0894841775 * a - 1.2914855480 * bb).pow(3)
        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        fun ch(v: Double) = (toSrgb(v).coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
