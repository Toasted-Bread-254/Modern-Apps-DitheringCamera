package com.vayunmathur.camera.dither

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

object DitherEngine {

    fun apply(
        source: Bitmap,
        mode: DitherMode,
        levels: Int = 16
    ): Bitmap {
        require(levels >= 2) { "levels must be at least 2" }

        return when (mode) {
            DitherMode.NONE -> quantiseGreyscale(source, levels)
            DitherMode.ORDERED_BAYER_2X2 -> ordered(source, levels, BAYER_2)
            DitherMode.ORDERED_BAYER_4X4 -> ordered(source, levels, BAYER_4)
            DitherMode.ORDERED_BAYER_8X8 -> ordered(source, levels, BAYER_8)
            DitherMode.FLOYD_STEINBERG -> errorDiffusion(source, levels, FloydSteinberg)
            DitherMode.ATKINSON -> errorDiffusion(source, levels, Atkinson)
            DitherMode.JARVIS_JUDICE_NINKE -> errorDiffusion(source, levels, Jarvis)
            DitherMode.STUCKI -> errorDiffusion(source, levels, Stucki)
            DitherMode.BURKES -> errorDiffusion(source, levels, Burkes)
            DitherMode.SIERRA -> errorDiffusion(source, levels, Sierra)
            DitherMode.SIERRA_LITE -> errorDiffusion(source, levels, SierraLite)
        }
    }

    private fun quantiseGreyscale(source: Bitmap, levels: Int): Bitmap {
        val width = source.width
        val height = source.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val step = 255.0 / (levels - 1)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val g = luminance(source.getPixel(x, y))
                val q = (g / step).roundToInt() * step
                val v = q.coerceIn(0.0, 255.0).toInt()
                out.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return out
    }

    private fun ordered(source: Bitmap, levels: Int, matrix: Array<IntArray>): Bitmap {
        val width = source.width
        val height = source.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val n = matrix.size
        val maxThreshold = n * n
        val step = 255.0 / (levels - 1)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val g = luminance(source.getPixel(x, y))
                val threshold = ((matrix[y % n][x % n] + 0.5) / maxThreshold - 0.5) * step
                val adjusted = (g + threshold).coerceIn(0.0, 255.0)
                val q = (adjusted / step).roundToInt() * step
                val v = q.coerceIn(0.0, 255.0).toInt()
                out.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return out
    }

    private fun errorDiffusion(source: Bitmap, levels: Int, kernel: DiffusionKernel): Bitmap {
        val width = source.width
        val height = source.height
        val values = Array(height) { DoubleArray(width) }

        for (y in 0 until height) {
            for (x in 0 until width) {
                values[y][x] = luminance(source.getPixel(x, y))
            }
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val step = 255.0 / (levels - 1)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val old = values[y][x].coerceIn(0.0, 255.0)
                val newValue = ((old / step).roundToInt() * step).coerceIn(0.0, 255.0)
                val error = old - newValue
                val v = newValue.toInt()
                out.setPixel(x, y, Color.rgb(v, v, v))

                for (entry in kernel.entries) {
                    val nx = x + entry.dx
                    val ny = y + entry.dy
                    if (nx in 0 until width && ny in 0 until height) {
                        values[ny][nx] += error * entry.weight / kernel.divisor
                    }
                }
            }
        }
        return out
    }

    private fun luminance(pixel: Int): Double {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    data class KernelEntry(val dx: Int, val dy: Int, val weight: Double)
    data class DiffusionKernel(val divisor: Double, val entries: List<KernelEntry>)

    private val FloydSteinberg = DiffusionKernel(16.0, listOf(KernelEntry(1, 0, 7.0), KernelEntry(-1, 1, 3.0), KernelEntry(0, 1, 5.0), KernelEntry(1, 1, 1.0)))
    private val Atkinson = DiffusionKernel(8.0, listOf(KernelEntry(1, 0, 1.0), KernelEntry(2, 0, 1.0), KernelEntry(-1, 1, 1.0), KernelEntry(0, 1, 1.0), KernelEntry(1, 1, 1.0), KernelEntry(0, 2, 1.0)))
    private val Jarvis = DiffusionKernel(48.0, listOf(KernelEntry(1, 0, 7.0), KernelEntry(2, 0, 5.0), KernelEntry(-2, 1, 3.0), KernelEntry(-1, 1, 5.0), KernelEntry(0, 1, 7.0), KernelEntry(1, 1, 5.0), KernelEntry(2, 1, 3.0), KernelEntry(-2, 2, 1.0), KernelEntry(-1, 2, 3.0), KernelEntry(0, 2, 5.0), KernelEntry(1, 2, 3.0), KernelEntry(2, 2, 1.0)))
    private val Stucki = DiffusionKernel(42.0, listOf(KernelEntry(1, 0, 8.0), KernelEntry(2, 0, 4.0), KernelEntry(-2, 1, 2.0), KernelEntry(-1, 1, 4.0), KernelEntry(0, 1, 8.0), KernelEntry(1, 1, 4.0), KernelEntry(2, 1, 2.0), KernelEntry(-2, 2, 1.0), KernelEntry(-1, 2, 2.0), KernelEntry(0, 2, 4.0), KernelEntry(1, 2, 2.0), KernelEntry(2, 2, 1.0)))
    private val Burkes = DiffusionKernel(32.0, listOf(KernelEntry(1, 0, 8.0), KernelEntry(2, 0, 4.0), KernelEntry(-2, 1, 2.0), KernelEntry(-1, 1, 4.0), KernelEntry(0, 1, 8.0), KernelEntry(1, 1, 4.0), KernelEntry(2, 1, 2.0)))
    private val Sierra = DiffusionKernel(32.0, listOf(KernelEntry(1, 0, 5.0), KernelEntry(2, 0, 3.0), KernelEntry(-2, 1, 2.0), KernelEntry(-1, 1, 4.0), KernelEntry(0, 1, 5.0), KernelEntry(1, 1, 4.0), KernelEntry(2, 1, 2.0), KernelEntry(-1, 2, 2.0), KernelEntry(0, 2, 3.0), KernelEntry(1, 2, 2.0)))
    private val SierraLite = DiffusionKernel(4.0, listOf(KernelEntry(1, 0, 2.0), KernelEntry(-1, 1, 1.0), KernelEntry(0, 1, 1.0)))

    private val BAYER_2 = arrayOf(intArrayOf(0, 2), intArrayOf(3, 1))
    private val BAYER_4 = arrayOf(intArrayOf(0, 8, 2, 10), intArrayOf(12, 4, 14, 6), intArrayOf(3, 11, 1, 9), intArrayOf(15, 7, 13, 5))
    private val BAYER_8 = arrayOf(
        intArrayOf(0, 48, 12, 60, 3, 51, 15, 63),
        intArrayOf(32, 16, 44, 28, 35, 19, 47, 31),
        intArrayOf(8, 56, 4, 52, 11, 59, 7, 55),
        intArrayOf(40, 24, 36, 20, 43, 27, 39, 23),
        intArrayOf(2, 50, 14, 62, 1, 49, 13, 61),
        intArrayOf(34, 18, 46, 30, 33, 17, 45, 29),
        intArrayOf(10, 58, 6, 54, 9, 57, 5, 53),
        intArrayOf(42, 26, 38, 22, 41, 25, 37, 21)
    )
}
