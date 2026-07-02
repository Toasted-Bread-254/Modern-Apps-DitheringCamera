package com.vayunmathur.library.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared on-device OCR engine using PaddleOCR (PP-OCR mobile) models on ONNX
 * Runtime (`com.microsoft.onnxruntime:onnxruntime-android`, MIT — no Google Play
 * Services, no ML Kit). Everything runs locally; the models ship as assets of
 * this module (assets are unrestricted for F-Droid). Both the Photos and PDF
 * apps depend on this one module so they share the models and code.
 *
 * Pipeline (classic PP-OCR, dependency-free):
 *  1. Text detection with the DB (Differentiable Binarization) model: the image
 *     is downscaled (long side ~[DET_LIMIT], both sides a multiple of 32),
 *     normalised with the PaddleOCR ImageNet mean/std, and run to a probability
 *     map. The map is thresholded and split into text regions via connected
 *     components; each region becomes an (axis-aligned) crop.
 *  2. Text recognition with the CRNN/SVTR model: each crop is resized to height
 *     [REC_H], normalised to [-1, 1], run, then CTC-decoded against the
 *     character dictionary.
 * Recognised lines are concatenated top-to-bottom into the returned text.
 *
 * Angle classification (the optional PP-OCR "cls" step) is intentionally skipped
 * to keep this lighter; scanned pages and gallery photos are almost always
 * upright. Drop a `cls.onnx` beside the others and extend [recognize] if needed.
 *
 * Assets (see [ASSET_DET], [ASSET_REC], [ASSET_DICT]) are sourced from the
 * RapidOCR project (https://github.com/RapidAI/RapidOCR, Apache-2.0), which
 * publishes ready-to-use PP-OCR detection/recognition ONNX models plus the
 * PaddleOCR character dictionary:
 *   - ocr/det.onnx    = PP-OCR English mobile text-detection model
 *   - ocr/rec.onnx    = PP-OCR English mobile text-recognition model
 *   - ocr/en_dict.txt = PaddleOCR `ppocr/utils/en_dict.txt` (recognition charset)
 * To swap in different PP-OCR ONNX models (e.g. multilingual), drop replacements
 * at these asset paths; the recognizer adapts to the model's output class count
 * automatically. If any asset is missing the engine is inert (returns empty
 * text, never crashes) — call [isAvailable] to check up front.
 *
 * Low-power by design: single-threaded ONNX sessions, inputs are downscaled
 * before detection, and one image is processed at a time. Sessions are created
 * lazily on first use and reused across calls; release them with [close].
 *
 * All heavy work runs off the main thread (Dispatchers.Default). Instances are
 * safe to reuse sequentially; concurrent calls are serialised internally.
 *
 * Usage:
 * ```
 * val ocr = OcrEngine(context)
 * val text = ocr.recognize(bitmap)   // suspend
 * ocr.close()                        // when done with a batch
 * ```
 */
class OcrEngine(private val context: Context) {

    /** One recognised, axis-aligned text region in source-bitmap pixel coordinates. */
    data class TextBox(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /** Full result: the joined [text] plus the individual [boxes] it came from. */
    data class OcrResult(val text: String, val boxes: List<TextBox>)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = Mutex()

    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dict: List<String> = emptyList()
    private var recChars: Array<String>? = null
    private var initTried = false

    /** True if the model + dictionary assets are present and could be loaded. */
    suspend fun isAvailable(): Boolean = lock.withLock { ensureInit() }

    /**
     * Recognise all text in [bitmap] and return it as a single string (lines
     * joined by newlines), or an empty string if nothing is found or the models
     * are unavailable. The caller's [bitmap] is not recycled.
     */
    suspend fun recognize(bitmap: Bitmap): String = recognizeDetailed(bitmap).text

    /** Like [recognize] but also returns the per-region [TextBox]es. */
    suspend fun recognizeDetailed(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        lock.withLock {
            if (!ensureInit()) return@withContext OcrResult("", emptyList())
            val src = bitmap.toSoftwareRgb()
            try {
                val boxes = detect(src)
                val results = ArrayList<TextBox>(boxes.size)
                for (box in boxes) {
                    val crop = cropSafely(src, box) ?: continue
                    val line = try {
                        recognizeCrop(crop)
                    } finally {
                        if (crop != src) crop.recycle()
                    }
                    if (!line.isNullOrBlank()) {
                        results.add(TextBox(line.trim(), box.left, box.top, box.right, box.bottom))
                    }
                }
                OcrResult(results.joinToString("\n") { it.text }.trim(), results)
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                OcrResult("", emptyList())
            } finally {
                if (src != bitmap) src.recycle()
            }
        }
    }

    /** Release the ONNX sessions. The shared [OrtEnvironment] is left open. */
    fun close() {
        try { detSession?.close() } catch (_: Exception) {}
        try { recSession?.close() } catch (_: Exception) {}
        detSession = null
        recSession = null
        recChars = null
        initTried = false
    }

    // ---- Initialisation ----

    /** Load models + dictionary and create the ONNX sessions once. */
    private fun ensureInit(): Boolean {
        if (detSession != null && recSession != null) return true
        if (initTried) return false
        initTried = true
        return try {
            val detBytes = readAsset(ASSET_DET) ?: run {
                Log.w(TAG, "Missing OCR asset $ASSET_DET"); return false
            }
            val recBytes = readAsset(ASSET_REC) ?: run {
                Log.w(TAG, "Missing OCR asset $ASSET_REC"); return false
            }
            val dictLines = readDict(ASSET_DICT) ?: run {
                Log.w(TAG, "Missing OCR asset $ASSET_DICT"); return false
            }
            val opts = OrtSession.SessionOptions().apply {
                // Single-threaded keeps sustained CPU/battery use low.
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            detSession = env.createSession(detBytes, opts)
            recSession = env.createSession(recBytes, opts)
            dict = dictLines
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise ONNX OCR", e)
            close()
            initTried = true
            false
        }
    }

    // ---- Detection (DB) ----

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun detect(src: Bitmap): List<Box> {
        val session = detSession ?: return emptyList()
        val (rw, rh) = detTargetSize(src.width, src.height)
        val resized = Bitmap.createScaledBitmap(src, rw, rh, true)
        val input = try {
            normalize(resized, rw, rh, MEAN_IMAGENET, STD_IMAGENET)
        } finally {
            if (resized != src) resized.recycle()
        }

        val inputName = session.inputNames.iterator().next()
        var prob = FloatArray(0)
        var pw = 0
        var ph = 0
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape // [1, 1, H, W]
                ph = shape[shape.size - 2].toInt()
                pw = shape[shape.size - 1].toInt()
                prob = FloatArray(ph * pw)
                out.floatBuffer.get(prob)
            }
        }
        if (pw == 0 || ph == 0) return emptyList()

        val boxes = boxesFromProbMap(prob, pw, ph)
        // Map from probability-map space back to the source bitmap.
        val sx = src.width.toFloat() / pw
        val sy = src.height.toFloat() / ph
        return boxes.map {
            Box(
                left = (it.left * sx).toInt(),
                top = (it.top * sy).toInt(),
                right = (it.right * sx).roundToInt(),
                bottom = (it.bottom * sy).roundToInt(),
            )
        }
    }

    private fun detTargetSize(w: Int, h: Int): Pair<Int, Int> {
        var ratio = 1f
        val maxSide = max(w, h)
        if (maxSide > DET_LIMIT) ratio = DET_LIMIT.toFloat() / maxSide
        fun toMul32(v: Int) = max(32, (v / 32.0).roundToInt() * 32)
        return toMul32((w * ratio).roundToInt()) to toMul32((h * ratio).roundToInt())
    }

    /**
     * Threshold the probability map and turn each connected text region into an
     * axis-aligned box. This is a dependency-free stand-in for PaddleOCR's
     * contour/min-area-rect + unclip step (which needs OpenCV); axis-aligned
     * boxes handle the horizontal text typical of documents and photos well.
     */
    private fun boxesFromProbMap(prob: FloatArray, w: Int, h: Int): List<Box> {
        val n = w * h
        val fg = BooleanArray(n) { prob[it] > DET_THRESH }
        val visited = BooleanArray(n)
        val stack = IntArray(n)
        val boxes = ArrayList<Box>()

        for (seed in 0 until n) {
            if (!fg[seed] || visited[seed]) continue
            var sp = 0
            stack[sp++] = seed
            visited[seed] = true
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = 0
            var maxY = 0
            var count = 0
            var probSum = 0f
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                count++
                probSum += prob[idx]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) { val nb = idx - 1; if (fg[nb] && !visited[nb]) { visited[nb] = true; stack[sp++] = nb } }
                if (x < w - 1) { val nb = idx + 1; if (fg[nb] && !visited[nb]) { visited[nb] = true; stack[sp++] = nb } }
                if (y > 0) { val nb = idx - w; if (fg[nb] && !visited[nb]) { visited[nb] = true; stack[sp++] = nb } }
                if (y < h - 1) { val nb = idx + w; if (fg[nb] && !visited[nb]) { visited[nb] = true; stack[sp++] = nb } }
            }
            if (count < DET_MIN_PIXELS) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < DET_MIN_SIDE || bh < DET_MIN_SIDE) continue
            if (probSum / count < DET_BOX_THRESH) continue
            // Approximate PaddleOCR's "unclip": grow the box a little to recover
            // characters clipped by the tight probability mask.
            val exp = (min(bw, bh) * DET_UNCLIP).roundToInt()
            boxes.add(
                Box(
                    left = (minX - exp).coerceAtLeast(0),
                    top = (minY - exp).coerceAtLeast(0),
                    right = (maxX + exp).coerceAtMost(w - 1),
                    bottom = (maxY + exp).coerceAtMost(h - 1),
                )
            )
            if (boxes.size >= DET_MAX_BOXES) break
        }
        // Reading order: top-to-bottom, then left-to-right within a row band.
        return boxes.sortedWith(compareBy({ it.top / READING_ROW_BAND }, { it.left }))
    }

    private fun cropSafely(src: Bitmap, box: Box): Bitmap? {
        val l = box.left.coerceIn(0, src.width - 1)
        val t = box.top.coerceIn(0, src.height - 1)
        val r = box.right.coerceIn(l + 1, src.width)
        val b = box.bottom.coerceIn(t + 1, src.height)
        val cw = r - l
        val ch = b - t
        if (cw < 2 || ch < 2) return null
        return try {
            Bitmap.createBitmap(src, l, t, cw, ch)
        } catch (e: Exception) {
            null
        }
    }

    // ---- Recognition (CRNN + CTC) ----

    private fun recognizeCrop(crop: Bitmap): String? {
        val session = recSession ?: return null
        val ratio = crop.width.toFloat() / crop.height
        val tw = ceil(REC_H * ratio).toInt().coerceIn(REC_MIN_W, REC_MAX_W)
        val resized = Bitmap.createScaledBitmap(crop, tw, REC_H, true)
        val input = try {
            normalize(resized, tw, REC_H, MEAN_HALF, STD_HALF)
        } finally {
            if (resized != crop) resized.recycle()
        }

        val inputName = session.inputNames.iterator().next()
        var text: String? = null
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, REC_H.toLong(), tw.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape // [1, T, C]
                val timeSteps = shape[1].toInt()
                val classes = shape[2].toInt()
                val data = FloatArray(timeSteps * classes)
                out.floatBuffer.get(data)
                text = ctcDecode(data, timeSteps, classes)
            }
        }
        return text
    }

    private fun ctcDecode(data: FloatArray, timeSteps: Int, classes: Int): String {
        val chars = recChars ?: buildChars(classes).also { recChars = it }
        val sb = StringBuilder()
        var last = -1
        for (t in 0 until timeSteps) {
            val base = t * classes
            var best = 0
            var bestVal = data[base]
            for (k in 1 until classes) {
                val v = data[base + k]
                if (v > bestVal) { bestVal = v; best = k }
            }
            if (best != last) {
                if (best != 0 && best < chars.size) sb.append(chars[best])
                last = best
            }
        }
        return sb.toString()
    }

    /**
     * Build the CTC index→char table. Index 0 is the CTC blank; indices 1..N map
     * to the dictionary. If the model has more classes than the dictionary (some
     * PP-OCR builds append a space class), the extras become spaces.
     */
    private fun buildChars(classes: Int): Array<String> = Array(classes) { i ->
        when {
            i == 0 -> ""
            i - 1 < dict.size -> dict[i - 1]
            else -> " "
        }
    }

    // ---- Shared helpers ----

    /** Ensure a readable ARGB bitmap: hardware bitmaps can't be read via getPixels. */
    private fun Bitmap.toSoftwareRgb(): Bitmap {
        return if (config == Bitmap.Config.HARDWARE || config == null) {
            copy(Bitmap.Config.ARGB_8888, false)
        } else {
            this
        }
    }

    /** Convert a bitmap to a normalised NCHW float array: (pixel/255 - mean)/std, RGB order. */
    private fun normalize(bmp: Bitmap, w: Int, h: Int, mean: FloatArray, std: FloatArray): FloatArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val area = w * h
        val out = FloatArray(3 * area)
        for (i in 0 until area) {
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            out[i] = (r - mean[0]) / std[0]
            out[area + i] = (g - mean[1]) / std[1]
            out[2 * area + i] = (b - mean[2]) / std[2]
        }
        return out
    }

    private fun readAsset(path: String): ByteArray? = try {
        context.assets.open(path).use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    private fun readDict(path: String): List<String>? = try {
        context.assets.open(path).bufferedReader().use { it.readLines() }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "OcrEngine"

        private const val ASSET_DET = "ocr/det.onnx"
        private const val ASSET_REC = "ocr/rec.onnx"
        private const val ASSET_DICT = "ocr/en_dict.txt"

        // Detection.
        private const val DET_LIMIT = 960
        private const val DET_THRESH = 0.3f
        private const val DET_BOX_THRESH = 0.5f
        private const val DET_MIN_PIXELS = 16
        private const val DET_MIN_SIDE = 3
        private const val DET_UNCLIP = 0.4f
        private const val DET_MAX_BOXES = 256
        private const val READING_ROW_BAND = 8

        // Recognition.
        private const val REC_H = 48
        private const val REC_MIN_W = 16
        private const val REC_MAX_W = 1024

        private val MEAN_IMAGENET = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD_IMAGENET = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val MEAN_HALF = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val STD_HALF = floatArrayOf(0.5f, 0.5f, 0.5f)
    }
}
