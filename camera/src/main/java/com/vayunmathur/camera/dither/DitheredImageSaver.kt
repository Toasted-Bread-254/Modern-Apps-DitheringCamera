package com.vayunmathur.camera.dither

import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DitheredImageSaver {

    suspend fun save(
        bitmap: Bitmap,
        outputFile: File,
        format: ArchiveFormat,
        quality: Int = 100
    ) = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { stream ->
            when (format) {
                ArchiveFormat.PNG,
                ArchiveFormat.ORIGINAL_PLUS_DITHERED -> bitmap.compress(Bitmap.CompressFormat.PNG, quality, stream)
                ArchiveFormat.WEBP_LOSSLESS -> {
                    if (Build.VERSION.SDK_INT >= 30) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, stream)
                    } else {
                        bitmap.compress(Bitmap.CompressFormat.PNG, quality, stream)
                    }
                }
            }
        }
    }
}
