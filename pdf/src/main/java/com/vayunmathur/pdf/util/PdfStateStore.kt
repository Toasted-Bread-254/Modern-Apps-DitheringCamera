package com.vayunmathur.pdf.util
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.pdf.view.PdfView

object PdfStateStore {
    private const val PREFS_NAME = "pdf_viewer_state"

    fun save(context: Context, uri: Uri, pdfView: PdfView) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(uri.toString(), pdfView.firstVisiblePage.toString())
        }
    }

    fun restore(context: Context, uri: Uri): (suspend (PdfView) -> Unit)? {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(uri.toString(), null) ?: return null
        val page = if (',' in value) value.split(',').getOrNull(1)?.toIntOrNull()
                   else value.toIntOrNull()
        return page?.let { p -> { pdfView: PdfView -> pdfView.scrollToPage(p) } }
    }
}
