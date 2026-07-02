package com.vayunmathur.photos.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.sqrt

/** Shapes the shape tool can draw. */
enum class PaintShape { Rectangle, Ellipse, Line }

/**
 * Flood-fill (paint bucket) from normalized ([nx],[ny]) with [colorArgb], for
 * contiguous pixels within [tolerance] (0..1) of the seed color.
 */
fun floodFillBitmap(
    bitmap: Bitmap,
    nx: Float,
    ny: Float,
    colorArgb: Int,
    tolerance: Float,
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    val sx = (nx * w).toInt().coerceIn(0, w - 1)
    val sy = (ny * h).toInt().coerceIn(0, h - 1)
    val seed = px[sy * w + sx]
    val sr = (seed ushr 16) and 0xFF; val sg = (seed ushr 8) and 0xFF; val sb = seed and 0xFF
    val tol = tolerance.coerceIn(0f, 1f) * 441f
    fun matches(c: Int): Boolean {
        val dr = ((c ushr 16) and 0xFF) - sr
        val dg = ((c ushr 8) and 0xFF) - sg
        val db = (c and 0xFF) - sb
        return sqrt((dr * dr + dg * dg + db * db).toFloat()) <= tol
    }
    val visited = BooleanArray(w * h)
    val stack = ArrayDeque<Int>()
    val start = sy * w + sx
    stack.addLast(start); visited[start] = true
    while (stack.isNotEmpty()) {
        val idx = stack.removeLast()
        if (!matches(px[idx])) continue
        px[idx] = colorArgb
        val cx = idx % w; val cy = idx / w
        if (cx > 0 && !visited[idx - 1]) { visited[idx - 1] = true; stack.addLast(idx - 1) }
        if (cx < w - 1 && !visited[idx + 1]) { visited[idx + 1] = true; stack.addLast(idx + 1) }
        if (cy > 0 && !visited[idx - w]) { visited[idx - w] = true; stack.addLast(idx - w) }
        if (cy < h - 1 && !visited[idx + w]) { visited[idx + w] = true; stack.addLast(idx + w) }
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

/** Overlay a linear gradient from normalized ([x0],[y0]) to ([x1],[y1]), fading
 *  from [color] to transparent. */
fun drawGradientBitmap(
    bitmap: Bitmap,
    x0: Float, y0: Float, x1: Float, y1: Float,
    color: Int,
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val transparent = color and 0x00FFFFFF
    val paint = Paint().apply {
        isAntiAlias = true
        shader = LinearGradient(
            x0 * w, y0 * h, x1 * w, y1 * h,
            color, transparent, Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    return out
}

/** Draw a filled [shape] over the normalized rect, in [colorArgb]. */
fun drawShapeBitmap(
    bitmap: Bitmap,
    shape: PaintShape,
    left: Float, top: Float, right: Float, bottom: Float,
    colorArgb: Int,
    strokeWidth: Float,
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val paint = Paint().apply {
        isAntiAlias = true
        this.color = colorArgb
    }
    val l = left * w; val t = top * h; val r = right * w; val b = bottom * h
    when (shape) {
        PaintShape.Rectangle -> canvas.drawRect(l, t, r, b, paint)
        PaintShape.Ellipse -> canvas.drawOval(l, t, r, b, paint)
        PaintShape.Line -> {
            paint.strokeWidth = strokeWidth * maxOf(w, h)
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(l, t, r, b, paint)
        }
    }
    return out
}
