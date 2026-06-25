package com.vayunmathur.email.ui

import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat

/**
 * Controller for an [HtmlEditor]. Owns the underlying [EditText] and exposes the
 * current body as an HTML string plus selection-based formatting actions.
 *
 * Unlike the markdown editor, formatting here is applied as real text spans
 * (bold/italic/underline/strike/bullets/links) and serialized to HTML — so the
 * email body is genuinely HTML, never markdown.
 */
class HtmlEditorController(initialHtml: String = "") {
    /** Live HTML of the body; recomposes readers on every edit. */
    var html by mutableStateOf(initialHtml)
        internal set

    /** Bumped whenever [setHtml] requests the view to reload from [html]. */
    internal var setVersion by mutableStateOf(0)

    /** Guards the TextWatcher while we mutate the field programmatically. */
    internal var updating = false

    internal var editText: EditText? = null

    /** Replace the whole body (draft load, signature swap). Reloads the view. */
    fun setHtml(newHtml: String) {
        html = newHtml
        setVersion++
    }

    private fun refresh() {
        editText?.text?.let { html = serializeHtml(it) }
    }

    fun toggleBold() = toggleStyle(Typeface.BOLD)
    fun toggleItalic() = toggleStyle(Typeface.ITALIC)
    fun toggleUnderline() = toggleCharSpan({ UnderlineSpan() }) { it is UnderlineSpan }
    fun toggleStrikethrough() = toggleCharSpan({ StrikethroughSpan() }) { it is StrikethroughSpan }

    private fun toggleStyle(style: Int) =
        toggleCharSpan({ StyleSpan(style) }) { it is StyleSpan && (it.style and style) != 0 }

    private fun toggleCharSpan(make: () -> Any, matches: (Any) -> Boolean) {
        val e = editText?.text ?: return
        val start = minOf(selStart(), selEnd())
        val end = maxOf(selStart(), selEnd())
        if (start >= end) return
        val overlapping = e.getSpans(start, end, Any::class.java).filter(matches)
        if (e.isFullyCovered(start, end, matches)) {
            overlapping.forEach { sp ->
                val ss = e.getSpanStart(sp)
                val se = e.getSpanEnd(sp)
                e.removeSpan(sp)
                if (ss < start) e.setSpan(make(), ss, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (se > end) e.setSpan(make(), end, se, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            e.setSpan(make(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        refresh()
    }

    /** Toggle a bullet on the paragraph containing the cursor. Serializes to <ul><li>. */
    fun toggleBullet() {
        val e = editText?.text ?: return
        val sel = minOf(selStart(), selEnd())
        val end = maxOf(selStart(), selEnd())
        val paraStart = e.lastIndexOf('\n', (sel - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val paraEnd = e.indexOf('\n', end).let { if (it < 0) e.length else it }
        if (paraEnd <= paraStart) return
        val existing = e.getSpans(paraStart, paraEnd, BulletSpan::class.java)
        if (existing.isNotEmpty()) existing.forEach { e.removeSpan(it) }
        else e.setSpan(BulletSpan(24), paraStart, paraEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        refresh()
    }

    /** Apply [url] as a link over the selection, or insert it as linked text. */
    fun applyLink(url: String) {
        val e = editText?.text ?: return
        if (url.isBlank()) return
        var start = minOf(selStart(), selEnd())
        var end = maxOf(selStart(), selEnd())
        if (start >= end) {
            updating = true
            e.insert(start, url)
            updating = false
            end = start + url.length
        }
        e.getSpans(start, end, URLSpan::class.java).forEach { e.removeSpan(it) }
        e.setSpan(URLSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        editText?.setSelection(end)
        refresh()
    }

    private fun selStart() = (editText?.selectionStart ?: 0).coerceAtLeast(0)
    private fun selEnd() = (editText?.selectionEnd ?: 0).coerceAtLeast(0)
}

@Composable
fun rememberHtmlEditorController(initialHtml: String = ""): HtmlEditorController =
    remember { HtmlEditorController(initialHtml) }

/**
 * An HTML-backed rich-text body field. Renders/edits real spans via a native
 * [EditText] and keeps [HtmlEditorController.html] in sync as HTML.
 */
@Composable
fun HtmlEditor(
    controller: HtmlEditorController,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val textColor = LocalContentColor.current.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val appliedVersion = remember { mutableIntStateOf(0) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            EditText(ctx).apply {
                background = null
                setTextColor(textColor)
                setHintTextColor(hintColor)
                hint = placeholder
                gravity = Gravity.TOP or Gravity.START
                setHorizontallyScrolling(false)
                isSingleLine = false
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                controller.editText = this
                controller.updating = true
                setText(HtmlCompat.fromHtml(controller.html, HtmlCompat.FROM_HTML_MODE_COMPACT))
                setSelection(text?.length ?: 0)
                controller.updating = false
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (!controller.updating && s != null) controller.html = serializeHtml(s)
                    }
                })
            }
        },
        update = { et ->
            val v = controller.setVersion
            if (v != appliedVersion.intValue) {
                controller.updating = true
                et.setText(HtmlCompat.fromHtml(controller.html, HtmlCompat.FROM_HTML_MODE_COMPACT))
                et.setSelection(0)
                controller.updating = false
                appliedVersion.intValue = v
            }
        },
    )
}

/** Bottom formatting toolbar driving an [HtmlEditorController]. */
@Composable
fun HtmlFormatToolbar(
    controller: HtmlEditorController,
    modifier: Modifier = Modifier,
) {
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf(TextFieldValue("https://")) }

    Surface(modifier = modifier.imePadding(), tonalElevation = 3.dp) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = { controller.toggleBold() }) { Text("B", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { controller.toggleItalic() }) { Text("I", fontStyle = FontStyle.Italic) }
            TextButton(onClick = { controller.toggleUnderline() }) { Text("U", textDecoration = TextDecoration.Underline) }
            TextButton(onClick = { controller.toggleStrikethrough() }) { Text("S", textDecoration = TextDecoration.LineThrough) }
            TextButton(onClick = { controller.toggleBullet() }) { Text("\u2022") }
            TextButton(onClick = { showLinkDialog = true }) { Text("Link") }
        }
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Insert link") },
            text = {
                OutlinedTextField(
                    value = linkUrl,
                    onValueChange = { linkUrl = it },
                    singleLine = true,
                    label = { Text("URL") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.applyLink(linkUrl.text.trim())
                    showLinkDialog = false
                    linkUrl = TextFieldValue("https://")
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun serializeHtml(s: Spanned): String =
    HtmlCompat.toHtml(s, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)

private fun Editable.isFullyCovered(start: Int, end: Int, matches: (Any) -> Boolean): Boolean {
    var pos = start
    while (pos < end) {
        val ok = getSpans(pos, pos + 1, Any::class.java).any {
            matches(it) && getSpanStart(it) <= pos && getSpanEnd(it) >= pos + 1
        }
        if (!ok) return false
        pos++
    }
    return true
}
