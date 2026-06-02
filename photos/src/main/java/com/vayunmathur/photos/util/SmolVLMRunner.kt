package com.vayunmathur.photos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Result from SmolVLM model inference containing OCR text and scene description.
 */
data class SmolVLMResult(
    val ocrText: String,
    val description: String,
)

/**
 * Runner for SmolVLM-256M-Instruct TFLite model.
 * Handles loading the model, preprocessing images, and running inference for OCR and scene description.
 */
class SmolVLMRunner(private val context: Context) {
    private var model: CompiledModel? = null
    private var tokenizer: SentencePieceTokenizer? = null
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "SmolVLMRunner"
        const val MODEL_FILE_NAME = "smalvlm-256m-instruct_q8_ekv2048_single_image.tflite"
        const val TOKENIZER_FILE_NAME = "tokenizer.model"
        private const val IMAGE_SIZE = 512
        private const val MAX_TOKENS = 256
    }

    /**
     * Initialize the CompiledModel and load the tokenizer.
     * Returns true if successful, false otherwise.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val externalDir = context.getExternalFilesDir(null)
            val modelFile = File(externalDir, MODEL_FILE_NAME)
            val tokenizerFile = File(externalDir, TOKENIZER_FILE_NAME)

            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: ${modelFile.absolutePath}")
                return@withContext false
            }
            if (!tokenizerFile.exists()) {
                Log.e(TAG, "Tokenizer file not found: ${tokenizerFile.absolutePath}")
                return@withContext false
            }

            // Load tokenizer
            val tok = SentencePieceTokenizer()
            if (!tok.load(tokenizerFile)) {
                Log.e(TAG, "Failed to load tokenizer")
                return@withContext false
            }
            tokenizer = tok
            Log.i(TAG, "Tokenizer loaded with ${tok.vocabSize} pieces")

            // Load model using LiteRT 2.x API
            val options = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
            }
            model = CompiledModel.create(modelFile.absolutePath, options)
            Log.i(TAG, "SmolVLM model initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SmolVLM model", e)
            false
        }
    }

    /**
     * Run inference on an image to extract OCR text and scene description.
     * @param uri URI of the image to process
     * @return SmolVLMResult containing ocrText and description, or null if failed
     */
    suspend fun runInference(uri: Uri): SmolVLMResult? = withContext(Dispatchers.IO) {
        try {
            val compiledModel = model ?: run {
                if (!initialize()) {
                    Log.e(TAG, "Failed to initialize model")
                    return@withContext null
                }
                model!!
            }
            val tok = tokenizer ?: run {
                Log.e(TAG, "Tokenizer not initialized")
                return@withContext null
            }

            // Load and preprocess image
            val bitmap = loadAndPreprocessImage(uri) ?: run {
                Log.e(TAG, "Failed to load image: $uri")
                return@withContext null
            }

            // Prepare image buffer
            val imageData = preprocessBitmap(bitmap)
            bitmap.recycle()

            // Tokenize prompt
            val prompt = "<image>\nExtract all visible text from this image and provide a brief scene description. Return JSON with 'text' and 'description' fields."
            val promptTokenIds = tok.encode(prompt)
            val paddedTokens = IntArray(MAX_TOKENS)
            promptTokenIds.copyInto(paddedTokens, endIndex = minOf(promptTokenIds.size, MAX_TOKENS))

            // Create input buffers and write data
            val inputBuffers = compiledModel.createInputBuffers()
            inputBuffers[0].writeFloat(imageData)
            inputBuffers[1].writeInt(paddedTokens)

            // Run inference (creates output buffers automatically and returns them)
            val outputBuffers = compiledModel.run(inputBuffers)

            // Read output tokens
            val outputTokens = outputBuffers[0].readInt()

            // Clean up buffers
            inputBuffers.forEach { it.close() }
            outputBuffers.forEach { it.close() }

            // Decode output tokens to text, stripping padding and EOS
            val validTokens = outputTokens.takeWhile { it != 0 && it != tok.eosTokenId }
            val outputText = tok.decode(validTokens.toIntArray())
            Log.d(TAG, "Model output: $outputText")

            // Parse JSON output
            parseOutput(outputText)
        } catch (e: Exception) {
            Log.e(TAG, "Error running inference", e)
            null
        }
    }

    /**
     * Load and preprocess image from URI.
     */
    private fun loadAndPreprocessImage(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                // Calculate sample size for memory efficiency
                var sampleSize = 1
                while (options.outWidth / sampleSize > IMAGE_SIZE * 2 || options.outHeight / sampleSize > IMAGE_SIZE * 2) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val original = BitmapFactory.decodeStream(stream, null, decodeOptions)
                    original?.let {
                        Bitmap.createScaledBitmap(it, IMAGE_SIZE, IMAGE_SIZE, true).also { scaled ->
                            if (scaled != it) it.recycle()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image", e)
            null
        }
    }

    /**
     * Preprocess bitmap into a float array for model input.
     * Normalizes pixels to [0, 1] in RGB order.
     */
    private fun preprocessBitmap(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        bitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        val floatArray = FloatArray(IMAGE_SIZE * IMAGE_SIZE * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            floatArray[i * 3] = ((pixel shr 16) and 0xFF) / 255.0f
            floatArray[i * 3 + 1] = ((pixel shr 8) and 0xFF) / 255.0f
            floatArray[i * 3 + 2] = (pixel and 0xFF) / 255.0f
        }
        return floatArray
    }

    /**
     * Parse model output JSON into SmolVLMResult.
     */
    private fun parseOutput(outputText: String): SmolVLMResult? {
        return try {
            val jsonStart = outputText.indexOf('{')
            val jsonEnd = outputText.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = outputText.substring(jsonStart, jsonEnd + 1)
                val element = json.parseToJsonElement(jsonStr).jsonObject
                val text = element["text"]?.jsonPrimitive?.content ?: ""
                val description = element["description"]?.jsonPrimitive?.content ?: ""
                SmolVLMResult(text, description)
            } else {
                SmolVLMResult("", outputText.trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing output", e)
            null
        }
    }

    /**
     * Check if both model and tokenizer files exist.
     */
    fun isModelAvailable(): Boolean {
        val externalDir = context.getExternalFilesDir(null)
        val modelFile = File(externalDir, MODEL_FILE_NAME)
        val tokenizerFile = File(externalDir, TOKENIZER_FILE_NAME)
        return modelFile.exists() && modelFile.length() > 0
            && tokenizerFile.exists() && tokenizerFile.length() > 0
    }

    /**
     * Get the model file path.
     */
    fun getModelFile(): File {
        return File(context.getExternalFilesDir(null), MODEL_FILE_NAME)
    }

    /**
     * Clean up resources.
     */
    fun close() {
        model?.close()
        model = null
        tokenizer = null
    }
}
