package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Apply a per-pixel ARGB transform to a fresh ARGB_8888 copy of [this]. */
private inline fun Bitmap.mapArgb(transform: (Int) -> Int): Bitmap {
    val w = width
    val h = height
    val out = copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) px[i] = transform(px[i])
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

private const val ALPHA_MASK = -0x1000000

/** Invert colors (photo negative). */
data class InvertAdj(val enabled: Boolean = true) : LayerAdjustment {
    override fun isIdentity(): Boolean = !enabled
    override val label: String get() = "Invert"
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = bitmap.mapArgb { c ->
        (c and ALPHA_MASK) or
            ((255 - ((c ushr 16) and 0xFF)) shl 16) or
            ((255 - ((c ushr 8) and 0xFF)) shl 8) or
            (255 - (c and 0xFF))
    }
}

/** Reduce each channel to [levels] discrete steps. */
data class PosterizeAdj(val levels: Int = 4) : LayerAdjustment {
    override fun isIdentity(): Boolean = levels >= 255 || levels < 2
    override val label: String get() = "Posterize"
    override fun applyToBitmap(bitmap: Bitmap): Bitmap {
        val n = levels.coerceIn(2, 255)
        val step = 255f / (n - 1)
        fun q(v: Int): Int = (Math.round(v / step) * step).toInt().coerceIn(0, 255)
        return bitmap.mapArgb { c ->
            (c and ALPHA_MASK) or (q((c ushr 16) and 0xFF) shl 16) or (q((c ushr 8) and 0xFF) shl 8) or q(c and 0xFF)
        }
    }
}

/** Convert to pure black/white by a luminance [level] (0..255). */
data class ThresholdAdj(val level: Int = 128) : LayerAdjustment {
    override fun isIdentity(): Boolean = false
    override val label: String get() = "Threshold"
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = bitmap.mapArgb { c ->
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        val lum = 0.299f * r + 0.587f * g + 0.114f * b
        val v = if (lum >= level) 255 else 0
        (c and ALPHA_MASK) or (v shl 16) or (v shl 8) or v
    }
}

/** Smart saturation: boosts less-saturated pixels more, protecting already-vivid ones. [amount] -100..100. */
data class VibranceAdj(val amount: Float = 0f) : LayerAdjustment {
    override fun isIdentity(): Boolean = amount == 0f
    override val label: String get() = "Vibrance"
    override fun applyToBitmap(bitmap: Bitmap): Bitmap {
        val amt = amount / 100f
        return bitmap.mapArgb { c ->
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val sat = (mx - mn) / 255f
            val factor = 1f + amt * (1f - sat)
            val avg = (r + g + b) / 3f
            fun adj(v: Int) = (avg + (v - avg) * factor).roundToInt().coerceIn(0, 255)
            (c and ALPHA_MASK) or (adj(r) shl 16) or (adj(g) shl 8) or adj(b)
        }
    }
}

/** Warming/cooling color wash. [color] ARGB tint, [density] 0..1 strength. */
data class PhotoFilterAdj(
    val color: Int = 0xFFEC8A00.toInt(),
    val density: Float = 0.25f,
) : LayerAdjustment {
    override fun isIdentity(): Boolean = density <= 0f
    override val label: String get() = "Photo Filter"
    override fun applyToBitmap(bitmap: Bitmap): Bitmap {
        val d = density.coerceIn(0f, 1f)
        val fr = (color ushr 16) and 0xFF
        val fg = (color ushr 8) and 0xFF
        val fb = color and 0xFF
        return bitmap.mapArgb { c ->
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            // Blend toward a multiply with the filter color, then re-add luminance
            // so overall brightness is roughly preserved.
            val mr = r * fr / 255
            val mg = g * fg / 255
            val mb = b * fb / 255
            val lumIn = 0.299f * r + 0.587f * g + 0.114f * b
            var nr = (r * (1 - d) + mr * d)
            var ng = (g * (1 - d) + mg * d)
            var nb = (b * (1 - d) + mb * d)
            val lumOut = 0.299f * nr + 0.587f * ng + 0.114f * nb
            if (lumOut > 0.01f) {
                val k = lumIn / lumOut
                nr *= k; ng *= k; nb *= k
            }
            (c and ALPHA_MASK) or (nr.roundToInt().coerceIn(0, 255) shl 16) or
                (ng.roundToInt().coerceIn(0, 255) shl 8) or nb.roundToInt().coerceIn(0, 255)
        }
    }
}

enum class SelectiveColorRange { Reds, Yellows, Greens, Cyans, Blues, Magentas, Neutrals }

/**
 * Selective color: adjust cyan/magenta/yellow (each -100..100) within one color
 * [range]. Cyan reduces red, magenta reduces green, yellow reduces blue,
 * weighted by how strongly a pixel belongs to the range.
 */
data class SelectiveColorAdj(
    val range: SelectiveColorRange = SelectiveColorRange.Reds,
    val cyan: Float = 0f,
    val magenta: Float = 0f,
    val yellow: Float = 0f,
) : LayerAdjustment {
    override fun isIdentity(): Boolean = cyan == 0f && magenta == 0f && yellow == 0f
    override val label: String get() = "Selective Color"

    override fun applyToBitmap(bitmap: Bitmap): Bitmap {
        val cShift = -cyan / 100f * 255f
        val mShift = -magenta / 100f * 255f
        val yShift = -yellow / 100f * 255f
        val targetHue = when (range) {
            SelectiveColorRange.Reds -> 0f
            SelectiveColorRange.Yellows -> 60f
            SelectiveColorRange.Greens -> 120f
            SelectiveColorRange.Cyans -> 180f
            SelectiveColorRange.Blues -> 240f
            SelectiveColorRange.Magentas -> 300f
            SelectiveColorRange.Neutrals -> -1f
        }
        val hsv = FloatArray(3)
        return bitmap.mapArgb { c ->
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val weight = if (targetHue < 0f) {
                // Neutrals: low saturation
                (1f - hsv[1]).coerceIn(0f, 1f)
            } else {
                var dh = kotlin.math.abs(hsv[0] - targetHue)
                if (dh > 180f) dh = 360f - dh
                val hueW = (1f - dh / 60f).coerceIn(0f, 1f)
                hueW * hsv[1]
            }
            if (weight <= 0f) c
            else {
                val nr = (r + cShift * weight).roundToInt().coerceIn(0, 255)
                val ng = (g + mShift * weight).roundToInt().coerceIn(0, 255)
                val nb = (b + yShift * weight).roundToInt().coerceIn(0, 255)
                (c and ALPHA_MASK) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }
}
