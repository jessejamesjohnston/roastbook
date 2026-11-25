package dev.roastbook.app.color

/**
 * The four colors one History row and its Detail screen use, as ARGB ints. [fallback] is true
 * when neither of the bag's swatches is colorful enough to count as its color.
 */
data class RowColors(
    val tintLight: Int,
    val tintDark: Int,
    val accentLight: Int,
    val accentDark: Int,
    val fallback: Boolean,
)

/**
 * The tint rule: "keep the hue, throw away everything else." The app dictates lightness and
 * chroma; only the hue comes from the bag. Pure and deterministic: the same swatches always give
 * the same colors, and each of the four colors keeps its chroma inside a fixed band.
 */
object TintRule {
    const val FALLBACK_HUE = 70.0
    private const val MIN_USABLE_CHROMA = 0.03
    private const val MIN_DEFINED_HUE_CHROMA = 0.005

    /**
     * Picks the source color from the bag's swatches (Vibrant first, then Muted) and derives the
     * row colors. When neither swatch is colorful enough the result is marked as fallback, keeping
     * a swatch's hue if it has one and using [FALLBACK_HUE] otherwise.
     *
     * @param vibrant Palette's Vibrant swatch (ARGB) or null.
     * @param muted Palette's Muted swatch (ARGB) or null.
     */
    fun compute(vibrant: Int?, muted: Int?): RowColors {
        val v = vibrant?.let(OklchConverter::fromArgb)
        val m = muted?.let(OklchConverter::fromArgb)
        val (source, fallback) = when {
            v != null && v.c >= MIN_USABLE_CHROMA -> v to false
            m != null && m.c >= MIN_USABLE_CHROMA -> m to false
            else -> {
                val any = v ?: m
                val hue = if (any != null && any.c >= MIN_DEFINED_HUE_CHROMA) any.h else FALLBACK_HUE
                Oklch(0.5, any?.c ?: 0.0, hue) to true
            }
        }
        return fromSource(source, fallback)
    }

    /** The row colors for one source color: its hue kept, lightness and chroma set by the rule. */
    fun fromSource(src: Oklch, fallback: Boolean): RowColors {
        val c = src.c
        val h = src.h
        return RowColors(
            tintLight = OklchConverter.toArgb(Oklch(0.915, (0.5 * c).coerceIn(0.028, 0.055), h)),
            tintDark = OklchConverter.toArgb(Oklch(0.30, (0.55 * c).coerceIn(0.025, 0.06), h)),
            accentLight = OklchConverter.toArgb(Oklch(0.52, (1.2 * c).coerceIn(0.06, 0.12), h)),
            accentDark = OklchConverter.toArgb(Oklch(0.80, (1.1 * c).coerceIn(0.06, 0.11), h)),
            fallback = fallback,
        )
    }

    /** The parchment defaults for a row whose hero has not been processed yet. */
    val neutral: RowColors = fromSource(Oklch(0.5, 0.0, FALLBACK_HUE), fallback = true)
}
