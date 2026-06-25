package com.vayunmathur.library.ui

import android.content.Context
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
class HtmlEditorController(initialHtml: String = "") : EditorFormatter {
    /** Live HTML of the body; recomposes readers on every edit. */
    var html by mutableStateOf(initialHtml)
        internal set

    override val supported = setOf(
        EditorFormat.BOLD, EditorFormat.ITALIC, EditorFormat.UNDERLINE,
        EditorFormat.STRIKETHROUGH, EditorFormat.BULLET, EditorFormat.LINK,
    )

    override fun toggle(format: EditorFormat) {
        when (format) {
            EditorFormat.BOLD -> toggleBold()
            EditorFormat.ITALIC -> toggleItalic()
            EditorFormat.UNDERLINE -> toggleUnderline()
            EditorFormat.STRIKETHROUGH -> toggleStrikethrough()
            EditorFormat.BULLET -> toggleBullet()
            EditorFormat.LINK -> {}
        }
    }

    /** Current selection, mirrored from the view so toolbars can react to it. */
    var selectionStart by mutableStateOf(0)
        internal set
    var selectionEnd by mutableStateOf(0)
        internal set

    /** Whether the editor currently has input focus (drives toolbar visibility). */
    var focused by mutableStateOf(false)
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

    /**
     * The link button state for the current selection/cursor, or null when the
     * button should be disabled (no selection and not on a link). Reads the
     * observable selection/html so callers recompute as the user moves around.
     */
    override fun linkContext(): LinkContext? = computeLinkContext()

    private fun computeLinkContext(): LinkContext? {
        val e = editText?.text ?: return null
        @Suppress("UNUSED_EXPRESSION") html // subscribe so edits re-evaluate
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, e.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, e.length)
        val span = urlSpanAt(e, start, end)
        if (span != null) {
            val ss = e.getSpanStart(span).coerceIn(0, e.length)
            val se = e.getSpanEnd(span).coerceIn(0, e.length)
            return LinkContext(editing = true, text = e.substring(ss, se), url = span.url ?: "")
        }
        return if (start < end) LinkContext(editing = false, text = e.substring(start, end), url = "") else null
    }

    /** Create or edit a link per [context], using the new [text]/[url]. */
    override fun applyLink(context: LinkContext, text: String, url: String) {
        val e = editText?.text ?: return
        if (url.isBlank()) return
        if (context.editing) {
            val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
            val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
            val span = urlSpanAt(e, start, end) ?: return
            val ss = e.getSpanStart(span)
            val se = e.getSpanEnd(span)
            e.removeSpan(span)
            updating = true
            e.replace(ss, se, text)
            updating = false
            e.setSpan(URLSpan(url), ss, ss + text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editText?.setSelection(ss + text.length)
        } else {
            val start = minOf(selStart(), selEnd()).coerceIn(0, e.length)
            val end = maxOf(selStart(), selEnd()).coerceIn(0, e.length)
            updating = true
            e.replace(start, end, text)
            updating = false
            val newEnd = start + text.length
            e.getSpans(start, newEnd, URLSpan::class.java).forEach { e.removeSpan(it) }
            e.setSpan(URLSpan(url), start, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            editText?.setSelection(newEnd)
        }
        refresh()
    }

    private fun selStart() = (editText?.selectionStart ?: 0).coerceAtLeast(0)
    private fun selEnd() = (editText?.selectionEnd ?: 0).coerceAtLeast(0)
}

@Composable
fun rememberHtmlEditorController(initialHtml: String = ""): HtmlEditorController =
    remember { HtmlEditorController(initialHtml) }

/** EditText that reports selection changes so Compose toolbars can react. */
private class RichEditText(context: Context) : EditText(context) {
    var onSelectionChange: ((Int, Int) -> Unit)? = null
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChange?.invoke(selStart, selEnd)
    }
}

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
            RichEditText(ctx).apply {
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
                onSelectionChange = { s, e ->
                    controller.selectionStart = s
                    controller.selectionEnd = e
                }
                setOnFocusChangeListener { _, hasFocus -> controller.focused = hasFocus }
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
    EditorBottomBar(modifier) {
        EditorBaseButtons(controller)
    }
}

private fun serializeHtml(s: Spanned): String =
    HtmlCompat.toHtml(s, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)

/** The URLSpan covering the selection, or the one just before a collapsed cursor. */
private fun urlSpanAt(e: Editable, start: Int, end: Int): URLSpan? {
    e.getSpans(start, maxOf(end, start), URLSpan::class.java).firstOrNull()?.let { return it }
    if (start == end && start > 0) {
        e.getSpans(start - 1, start, URLSpan::class.java).firstOrNull()?.let { return it }
    }
    return null
}

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
