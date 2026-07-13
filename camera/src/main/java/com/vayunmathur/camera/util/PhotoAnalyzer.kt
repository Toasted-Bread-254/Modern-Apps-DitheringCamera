package com.vayunmathur.camera.util

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Combined PHOTO-stream analyzer. The ImageAnalysis use case can only host one analyzer, so this
 * reads the Y (luminance) plane once and both (1) samples average brightness for night-mode
 * detection and (2) runs the ZXing QR decode. Replaces the standalone [QrAnalyzer] in PHOTO mode.
 */
class PhotoAnalyzer(
    private val onLuminance: (Float) -> Unit,
    private val onQrDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Average luminance over a strided subsample of the Y plane (kept cheap).
        var sum = 0L
        var count = 0
        var i = 0
        while (i < bytes.size) {
            sum += (bytes[i].toInt() and 0xFF)
            count++
            i += 16
        }
        if (count > 0) onLuminance(sum.toFloat() / count)

        val source = PlanarYUVLuminanceSource(
            bytes,
            imageProxy.width,
            imageProxy.height,
            0, 0,
            imageProxy.width,
            imageProxy.height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        try {
            val result = reader.decodeWithState(bitmap)
            onQrDetected(result.text)
        } catch (_: NotFoundException) {
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }
}
