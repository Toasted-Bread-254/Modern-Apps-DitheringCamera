package com.vayunmathur.photos.data

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class BlurMode { Radial, Linear, Lens }

data class BlurParams(
    val mode: BlurMode = BlurMode.Radial,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radius: Float = 0.3f,
    val intensity: Float = 10f,
    val feather: Float = 0.3f,
    val angle: Float = 0f,
) {
    fun isIdentity(): Boolean = intensity == 0f
}

private fun generateGaussianKernel(radius: Int): FloatArray {
    val size = radius * 2 + 1
    val kernel = FloatArray(size)
    val sigma = radius / 3f
    var sum = 0f
    for (i in 0 until size) {
        val x = (i - radius).toFloat()
        kernel[i] = exp(-(x * x) / (2f * sigma * sigma))
        sum += kernel[i]
    }
    for (i in 0 until size) kernel[i] /= sum
    return kernel
}

private fun gaussianBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
    if (radius <= 0) return pixels.copyOf()
    val kernel = generateGaussianKernel(radius)
    val temp = IntArray(w * h)
    val output = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var rr = 0f; var gg = 0f; var bb = 0f; var aa = 0f
            for (k in -radius..radius) {
                val sx = (x + k).coerceIn(0, w - 1)
                val px = pixels[y * w + sx]
                val weight = kernel[k + radius]
                aa += ((px shr 24) and 0xFF) * weight
                rr += ((px shr 16) and 0xFF) * weight
                gg += ((px shr 8) and 0xFF) * weight
                bb += (px and 0xFF) * weight
            }
            temp[y * w + x] = (aa.toInt().coerceIn(0, 255) shl 24) or
                    (rr.toInt().coerceIn(0, 255) shl 16) or
                    (gg.toInt().coerceIn(0, 255) shl 8) or
                    bb.toInt().coerceIn(0, 255)
        }
    }
    for (y in 0 until h) {
        for (x in 0 until w) {
            var rr = 0f; var gg = 0f; var bb = 0f; var aa = 0f
            for (k in -radius..radius) {
                val sy = (y + k).coerceIn(0, h - 1)
                val px = temp[sy * w + x]
                val weight = kernel[k + radius]
                aa += ((px shr 24) and 0xFF) * weight
                rr += ((px shr 16) and 0xFF) * weight
                gg += ((px shr 8) and 0xFF) * weight
                bb += (px and 0xFF) * weight
            }
            output[y * w + x] = (aa.toInt().coerceIn(0, 255) shl 24) or
                    (rr.toInt().coerceIn(0, 255) shl 16) or
                    (gg.toInt().coerceIn(0, 255) shl 8) or
                    bb.toInt().coerceIn(0, 255)
        }
    }
    return output
}

private fun generateMask(params: BlurParams, w: Int, h: Int): FloatArray {
    val mask = FloatArray(w * h)
    when (params.mode) {
        BlurMode.Radial -> {
            val cx = params.centerX * w
            val cy = params.centerY * h
            val r = params.radius * max(w, h)
            val featherDist = params.feather * max(w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dist = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()).toFloat()
                    mask[y * w + x] = if (dist < r) 0f
                    else ((dist - r) / featherDist.coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
            }
        }
        BlurMode.Linear -> {
            val cx = params.centerX * w
            val cy = params.centerY * h
            val r = params.radius * max(w, h)
            val featherDist = params.feather * max(w, h)
            val rad = Math.toRadians(params.angle.toDouble())
            val nx = -kotlin.math.sin(rad).toFloat()
            val ny = kotlin.math.cos(rad).toFloat()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dist = kotlin.math.abs((x - cx) * nx + (y - cy) * ny)
                    mask[y * w + x] = if (dist < r) 0f
                    else ((dist - r) / featherDist.coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
            }
        }
        BlurMode.Lens -> {
            val cx = params.centerX * w
            val cy = params.centerY * h
            val r = params.radius * max(w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dist = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()).toFloat()
                    mask[y * w + x] = if (dist > r) 1f else 0f
                }
            }
        }
    }
    return mask
}

fun BlurParams.applyBlurToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val blurRadius = (intensity * 0.5f).toInt().coerceIn(1, 25)
    val blurred = gaussianBlur(pixels, w, h, blurRadius)
    val mask = generateMask(this, w, h)
    val output = IntArray(w * h)
    for (i in pixels.indices) {
        val m = mask[i]
        val origA = (pixels[i] shr 24) and 0xFF
        val origR = (pixels[i] shr 16) and 0xFF
        val origG = (pixels[i] shr 8) and 0xFF
        val origB = pixels[i] and 0xFF
        val blurA = (blurred[i] shr 24) and 0xFF
        val blurR = (blurred[i] shr 16) and 0xFF
        val blurG = (blurred[i] shr 8) and 0xFF
        val blurB = blurred[i] and 0xFF
        val a = (origA + (blurA - origA) * m).toInt().coerceIn(0, 255)
        val r = (origR + (blurR - origR) * m).toInt().coerceIn(0, 255)
        val g = (origG + (blurG - origG) * m).toInt().coerceIn(0, 255)
        val b = (origB + (blurB - origB) * m).toInt().coerceIn(0, 255)
        output[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(output, 0, w, 0, 0, w, h)
    return result
}
