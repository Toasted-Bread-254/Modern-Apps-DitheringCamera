package com.vayunmathur.pdf.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.pdf.ocr.OcrProvider
import androidx.pdf.ocr.OcrResult
import androidx.pdf.ocr.OcrText
import com.vayunmathur.library.ocr.OcrEngine

/**
 * [OcrProvider] backed by the shared [OcrEngine] (PaddleOCR PP-OCR mobile on
 * ONNX Runtime, from the `:library:ocr` module). Passing this into the AndroidX
 * [androidx.pdf.compose.PdfViewer] lets the library natively use our on-device
 * OCR for text selection and search on scanned / image-only PDF pages.
 *
 * PaddleOCR detects text at the *line* level. The AndroidX PDF viewer, however,
 * works in terms of words (selection, tap-to-select, search). We therefore split
 * each recognised line into words and estimate each word's bounding [Rect] by
 * proportionally dividing the line box across the line's characters — good enough
 * for selection and search highlighting.
 *
 * Coordinate space: [OcrEngine.recognizeDetailed] already returns its boxes in
 * the pixel space of the bitmap passed to it (it maps the detector's internally
 * downscaled probability-map coordinates back up to the source bitmap), so the
 * rects here are in the same space as the [recognizeText] input bitmap and need
 * no further rescaling.
 */
class PaddleOcrProvider(context: Context) : OcrProvider {

    // Cheap to construct; the ONNX sessions load lazily on first recognizeText.
    private val engine = OcrEngine(context.applicationContext)

    override suspend fun recognizeText(image: Bitmap): OcrResult {
        val detailed = engine.recognizeDetailed(image)
        val words = ArrayList<Word>()
        for (line in detailed.boxes) {
            words += splitLineIntoWords(line)
        }
        return PaddleOcrResult(words)
    }

    override fun close() {
        engine.close()
    }

    /** A single word with its estimated bounding box, in input-bitmap pixels. */
    private class Word(val text: String, val rect: Rect)

    /**
     * Split a detected line into words, estimating each word's rect by dividing
     * the line box width evenly across the line's characters (spaces included).
     */
    private fun splitLineIntoWords(line: OcrEngine.TextBox): List<Word> {
        val text = line.text
        if (text.isBlank()) return emptyList()
        val left = minOf(line.left, line.right)
        val right = maxOf(line.left, line.right)
        val top = minOf(line.top, line.bottom)
        val bottom = maxOf(line.top, line.bottom)
        val totalChars = text.length.coerceAtLeast(1)
        val charWidth = (right - left).toFloat() / totalChars

        val result = ArrayList<Word>()
        val matcher = Regex("\\S+")
        for (m in matcher.findAll(text)) {
            val startChar = m.range.first
            val endChar = m.range.last + 1 // exclusive
            val wl = (left + startChar * charWidth).toInt()
            val wr = (left + endChar * charWidth).toInt().coerceAtLeast(wl + 1)
            result += Word(m.value, Rect(wl, top, wr, bottom))
        }
        return result
    }

    /** [OcrResult] over a flat, reading-order list of [Word]s. */
    private class PaddleOcrResult(private val words: List<Word>) : OcrResult {

        override fun getAllText(): OcrText =
            OcrText(words.joinToString(" ") { it.text }, words.map { it.rect })

        override fun getText(startX: Int, startY: Int, endX: Int, endY: Int): OcrText {
            val query = Rect(minOf(startX, endX), minOf(startY, endY), maxOf(startX, endX), maxOf(startY, endY))
            val hits = words.filter { Rect.intersects(it.rect, query) }
            return OcrText(hits.joinToString(" ") { it.text }, hits.map { it.rect })
        }

        override fun getWordAt(x: Int, y: Int): OcrText {
            val word = words.firstOrNull { it.rect.contains(x, y) }
            return if (word != null) OcrText(word.text, listOf(word.rect)) else EMPTY
        }

        override fun getSearchBounds(searchTerm: String, ignoreCase: Boolean): List<List<Rect>> {
            if (searchTerm.isBlank() || words.isEmpty()) return emptyList()

            // Build the concatenated text with a single space between words, and a
            // map from each character position back to its word index (-1 for the
            // separator spaces). This lets a match span multiple words.
            val sb = StringBuilder()
            val charToWord = ArrayList<Int>()
            for ((i, word) in words.withIndex()) {
                if (i > 0) {
                    sb.append(' ')
                    charToWord.add(-1)
                }
                for (c in word.text) {
                    sb.append(c)
                    charToWord.add(i)
                }
            }

            val haystack = sb.toString()
            val matches = ArrayList<List<Rect>>()

            var from = 0
            while (true) {
                val idx = haystack.indexOf(searchTerm, from, ignoreCase)
                if (idx < 0) break
                val end = idx + searchTerm.length
                from = end.coerceAtLeast(idx + 1)

                // Collect the distinct words this occurrence spans, in order.
                val rects = ArrayList<Rect>()
                var lastWord = -1
                for (p in idx until end) {
                    val w = charToWord[p]
                    if (w >= 0 && w != lastWord) {
                        rects.add(words[w].rect)
                        lastWord = w
                    }
                }
                if (rects.isNotEmpty()) matches.add(rects)
            }
            return matches
        }

        companion object {
            private val EMPTY = OcrText("", emptyList())
        }
    }
}
