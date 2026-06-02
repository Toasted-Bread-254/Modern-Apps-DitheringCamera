package com.vayunmathur.photos.util

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class OCRManager(private val context: Context) {

    private val smolVLMRunner = SmolVLMRunner(context)

    suspend fun runOCR(uri: Uri): Pair<String, String>? {
        var attempts = 0
        Log.d("OCRManager", "Starting OCR")

        // Check if model is available
        if (!smolVLMRunner.isModelAvailable()) {
            Log.e("OCRManager", "Model file not available")
            return null
        }

        while (attempts < 3) {
            attempts++
            val result = withTimeoutOrNull(120000) { // 2 minute timeout per photo (model inference can be slow)
                performInference(uri)
            }
            if (result != null) {
                return result
            }
            Log.w("OCRManager", "Inference timed out for $uri (Attempt $attempts)")
            if (attempts < 3) {
                delay(5000) // Wait 5 seconds before retry
            }
        }
        return null
    }

    private suspend fun performInference(uri: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val result = smolVLMRunner.runInference(uri)
            if (result != null) {
                Pair(result.ocrText, result.description)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("OCRManager", "Error running inference", e)
            null
        }
    }

    fun isModelAvailable(): Boolean {
        return smolVLMRunner.isModelAvailable()
    }

    fun cleanup() {
        smolVLMRunner.close()
    }
}
