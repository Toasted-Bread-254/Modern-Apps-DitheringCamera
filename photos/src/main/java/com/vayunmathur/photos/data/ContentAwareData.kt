package com.vayunmathur.photos.data

import android.graphics.Bitmap

/**
 * Content-aware fill: remove the region marked by [holeMask] (a normalized mask
 * at [maskW] x [maskH]) by diffusing surrounding colors inward. A simple Jacobi
 * relaxation over the hole's bounding box — smooth, keyless, and good for
 * removing objects on reasonably uniform backgrounds.
 */
fun inpaintBitmap(
    bitmap: Bitmap,
    holeMask: FloatArray,
    maskW: Int,
    maskH: Int,
    passes: Int = 60,
): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)

    val hole = BooleanArray(w * h)
    var minX = w; var minY = h; var maxX = -1; var maxY = -1
    for (y in 0 until h) {
        val my = (y * maskH / h).coerceIn(0, maskH - 1)
        for (x in 0 until w) {
            val mx = (x * maskW / w).coerceIn(0, maskW - 1)
            if (holeMask[my * maskW + mx] >= 0.5f) {
                hole[y * w + x] = true
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < minX) return out // nothing to fill

    val bw = maxX - minX + 1
    val bh = maxY - minY + 1
    val r = FloatArray(bw * bh)
    val g = FloatArray(bw * bh)
    val b = FloatArray(bw * bh)
    val holeBox = BooleanArray(bw * bh)
    for (y in 0 until bh) {
        for (x in 0 until bw) {
            val gi = (minY + y) * w + (minX + x)
            val c = px[gi]
            val bi = y * bw + x
            r[bi] = ((c ushr 16) and 0xFF).toFloat()
            g[bi] = ((c ushr 8) and 0xFF).toFloat()
            b[bi] = (c and 0xFF).toFloat()
            holeBox[bi] = hole[gi]
        }
    }

    val nr = r.copyOf(); val ng = g.copyOf(); val nb = b.copyOf()
    fun sample(arr: FloatArray, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, bw - 1); val cy = y.coerceIn(0, bh - 1)
        return arr[cy * bw + cx]
    }
    repeat(passes) {
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val bi = y * bw + x
                if (!holeBox[bi]) continue
                nr[bi] = (sample(r, x - 1, y) + sample(r, x + 1, y) + sample(r, x, y - 1) + sample(r, x, y + 1)) / 4f
                ng[bi] = (sample(g, x - 1, y) + sample(g, x + 1, y) + sample(g, x, y - 1) + sample(g, x, y + 1)) / 4f
                nb[bi] = (sample(b, x - 1, y) + sample(b, x + 1, y) + sample(b, x, y - 1) + sample(b, x, y + 1)) / 4f
            }
        }
        System.arraycopy(nr, 0, r, 0, r.size)
        System.arraycopy(ng, 0, g, 0, g.size)
        System.arraycopy(nb, 0, b, 0, b.size)
    }

    for (y in 0 until bh) {
        for (x in 0 until bw) {
            val bi = y * bw + x
            if (!holeBox[bi]) continue
            val gi = (minY + y) * w + (minX + x)
            val a = px[gi] and -0x1000000
            px[gi] = a or (r[bi].toInt().coerceIn(0, 255) shl 16) or
                (g[bi].toInt().coerceIn(0, 255) shl 8) or b[bi].toInt().coerceIn(0, 255)
        }
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}
