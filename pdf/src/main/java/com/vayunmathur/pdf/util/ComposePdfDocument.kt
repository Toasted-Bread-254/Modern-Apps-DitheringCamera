package com.vayunmathur.pdf.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A mutable PDF being composed in the "cut and glue" screen: starts empty and
 * grows as whole PDFs or images are appended; pages can be reordered/removed.
 * Backed by the native Rust document behind [handle].
 */
class ComposePdfDocument private constructor(private val handle: Long) {

    /** Current number of pages (cheap native call). */
    fun pageCount(): Int = PdfNative.getPageCount(handle)

    suspend fun renderPage(index: Int): SafePdfPage? = withContext(Dispatchers.IO) {
        PdfNative.renderPage(handle, index)?.let { SafePdfParser.parse(it) }
    }

    /** Append all pages of PDF [bytes]; returns pages added. */
    suspend fun appendPdf(bytes: ByteArray): Int = withContext(Dispatchers.IO) {
        PdfNative.appendPdf(handle, bytes)
    }

    /** Append a JPEG image ([w]x[h]) as a new page; returns 1 on success. */
    suspend fun appendImage(jpeg: ByteArray, w: Int, h: Int): Int = withContext(Dispatchers.IO) {
        PdfNative.appendImagePage(handle, jpeg, w, h)
    }

    suspend fun movePage(from: Int, to: Int): Boolean = withContext(Dispatchers.IO) {
        PdfNative.movePage(handle, from, to)
    }

    suspend fun removePage(index: Int): Boolean = withContext(Dispatchers.IO) {
        PdfNative.removePage(handle, index)
    }

    suspend fun save(): ByteArray? = withContext(Dispatchers.IO) { PdfNative.saveDocument(handle) }

    fun close() = PdfNative.closeDocument(handle)

    companion object {
        fun create(): ComposePdfDocument = ComposePdfDocument(PdfNative.createEmptyDocument())
    }
}
