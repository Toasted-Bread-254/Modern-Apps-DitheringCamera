package com.vayunmathur.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.parseMarkdown

/**
 * A reusable Markdown body editor: a formatting toolbar plus a text field that
 * renders the markdown live (bold shows bold, headings grow, etc.) using the
 * shared [parseMarkdown]. The caller owns the raw markdown text via [value] /
 * [onValueChange]; the styled rendering is derived for display only so the
 * cursor/selection stay aligned with the underlying text.
 *
 * The formatting logic (toggleInlineFormat, toggleLinePrefix, …) is shared with
 * the notes editor so the two never diverge. For HTML consumers (e.g. email),
 * convert the resulting markdown with [markdownToHtml] before sending.
 */
@Composable
fun MarkdownEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    Column(modifier) {
        MarkdownToolbar(value = value, onValueChange = onValueChange)
        val styled = value.copy(
            annotatedString = parseMarkdown(
                value.text,
                showMarkers = false,
                process = false,
                softWrap = false,
            )
        )
        BasicTextField(
            value = styled,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
            cursorBrush = SolidColor(LocalContentColor.current),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** Standalone formatting toolbar driving a markdown [TextFieldValue]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkdownToolbar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    var showHeadingMenu by remember { mutableStateOf(false) }
    val activeBg = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))

    fun apply(transform: (TextFieldValue) -> TextFieldValue) = onValueChange(transform(value))

    Surface(tonalElevation = 2.dp) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val boldActive = isInlineFormatActive(value.text, value.selection, "**")
            val italicActive = isInlineFormatActive(value.text, value.selection, "*")
            val strikeActive = isInlineFormatActive(value.text, value.selection, "~~")
            val quoteActive = isLinePrefixActive(value.text, value.selection, "> ")
            val bulletActive = isLinePrefixActive(value.text, value.selection, "- ")
            val numberedActive = isLinePrefixActive(value.text, value.selection, "1. ")
            val headingActive = getActiveHeadingLevel(value.text, value.selection.start) != null

            TextButton(
                onClick = { apply { toggleInlineFormat(it, "**") } },
                modifier = if (boldActive) activeBg else Modifier,
            ) { Text("B", fontWeight = FontWeight.Bold) }

            TextButton(
                onClick = { apply { toggleInlineFormat(it, "*") } },
                modifier = if (italicActive) activeBg else Modifier,
            ) { Text("I", fontStyle = FontStyle.Italic) }

            TextButton(
                onClick = { apply { toggleInlineFormat(it, "~~") } },
                modifier = if (strikeActive) activeBg else Modifier,
            ) { Text("S", textDecoration = TextDecoration.LineThrough) }

            Box {
                TextButton(
                    onClick = { showHeadingMenu = true },
                    modifier = if (headingActive) activeBg else Modifier,
                ) { Text("H") }
                DropdownMenu(expanded = showHeadingMenu, onDismissRequest = { showHeadingMenu = false }) {
                    (1..3).forEach { level ->
                        DropdownMenuItem(
                            text = { Text("Heading $level") },
                            onClick = {
                                apply { insertHeading(it, level) }
                                showHeadingMenu = false
                            },
                        )
                    }
                }
            }

            TextButton(
                onClick = { apply { toggleLinePrefix(it, "> ") } },
                modifier = if (quoteActive) activeBg else Modifier,
            ) { Text("\u201C\u201D") }

            TextButton(
                onClick = { apply { toggleLinePrefix(it, "- ") } },
                modifier = if (bulletActive) activeBg else Modifier,
            ) { Text("\u2022") }

            TextButton(
                onClick = { apply { toggleLinePrefix(it, "1. ") } },
                modifier = if (numberedActive) activeBg else Modifier,
            ) { Text("1.") }

            TextButton(onClick = { apply { insertLink(it) } }) { Text("Link") }
        }
    }
}

// ---------------------------------------------------------------------------
// Markdown editing helpers (shared by the notes and email editors).
// ---------------------------------------------------------------------------

private fun isCheckboxPrefix(line: String) =
    line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ")

private fun stripLinePrefix(line: String): String {
    Regex("^#{1,6} ").find(line)?.let { return line.substring(it.value.length) }
    if (isCheckboxPrefix(line)) return line.substring(6)
    if (line.startsWith("- ")) return line.substring(2)
    Regex("^\\d+\\. ").find(line)?.let { return line.substring(it.value.length) }
    if (line.startsWith("> ")) return line.substring(2)
    return line
}

private fun getLinePrefix(line: String): String {
    Regex("^#{1,6} ").find(line)?.let { return it.value }
    if (isCheckboxPrefix(line)) return line.substring(0, 6)
    if (line.startsWith("- ")) return "- "
    Regex("^\\d+\\. ").find(line)?.let { return it.value }
    if (line.startsWith("> ")) return "> "
    return ""
}

private fun getSelectedLines(text: String, selection: TextRange): Pair<Int, Int> {
    val start = minOf(selection.start, selection.end).coerceIn(0, text.length)
    val end = maxOf(selection.start, selection.end).coerceIn(0, text.length)
    val blockStart = text.lastIndexOf('\n', start - 1) + 1
    val effectiveEnd = if (start != end && end > 0 && text.getOrNull(end - 1) == '\n') end - 1 else end
    val blockEnd = text.indexOf('\n', effectiveEnd).let { if (it == -1) text.length else it }
    return blockStart to blockEnd.coerceAtLeast(blockStart)
}

private fun matchesPrefix(line: String, prefix: String): Boolean = when {
    prefix == "1. " -> Regex("^\\d+\\. ").containsMatchIn(line)
    prefix == "- [ ] " -> isCheckboxPrefix(line)
    prefix == "- " -> line.startsWith("- ") && !isCheckboxPrefix(line)
    else -> line.startsWith(prefix)
}

private fun computeBlockSelection(
    text: String,
    selection: TextRange,
    blockStart: Int,
    lines: List<String>,
    newLines: List<String>,
    newBlockText: String,
): TextRange = if (selection.collapsed) {
    val lineIndex = text.substring(blockStart, selection.start).count { it == '\n' }
    val diff = newLines[lineIndex].length - lines[lineIndex].length
    TextRange((selection.start + diff).coerceAtLeast(blockStart))
} else {
    TextRange(blockStart, blockStart + newBlockText.length)
}

private fun hasInlineMarker(content: String, marker: String): Boolean {
    if (content.length < marker.length * 2) return false
    if (!content.startsWith(marker) || !content.endsWith(marker)) return false
    if (marker == "*" && content.startsWith("**") && !content.startsWith("***")) return false
    if (marker == "*" && content.endsWith("**") && !content.endsWith("***")) return false
    return true
}

fun toggleInlineFormat(value: TextFieldValue, marker: String): TextFieldValue {
    val text = value.text
    val selection = value.selection
    if (selection.collapsed) {
        val newText = text.substring(0, selection.start) + marker + marker + text.substring(selection.start)
        return value.copy(text = newText, selection = TextRange(selection.start + marker.length))
    }
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val lines = text.substring(blockStart, blockEnd).split("\n")

    val allHaveMarker = lines.all { line ->
        line.isBlank() || hasInlineMarker(line.substring(getLinePrefix(line).length), marker)
    }

    val newLines = lines.map { line ->
        if (line.isBlank()) line
        else {
            val prefix = getLinePrefix(line)
            val content = line.substring(prefix.length)
            if (allHaveMarker) {
                prefix + content.substring(marker.length, content.length - marker.length)
            } else {
                if (hasInlineMarker(content, marker)) line
                else prefix + marker + content + marker
            }
        }
    }

    val newBlockText = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlockText + text.substring(blockEnd)
    return value.copy(text = newText, selection = TextRange(blockStart, blockStart + newBlockText.length))
}

fun toggleLinePrefix(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val lines = text.substring(blockStart, blockEnd).split("\n")

    val allHavePrefix = lines.all { matchesPrefix(it, prefix) }

    val newLines = if (allHavePrefix) {
        lines.map { stripLinePrefix(it) }
    } else {
        lines.mapIndexed { index, line ->
            val stripped = stripLinePrefix(line)
            if (prefix == "1. ") "${index + 1}. $stripped" else prefix + stripped
        }
    }

    val newBlockText = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlockText + text.substring(blockEnd)
    return value.copy(text = newText, selection = computeBlockSelection(text, selection, blockStart, lines, newLines, newBlockText))
}

fun insertHeading(value: TextFieldValue, level: Int): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val lines = text.substring(blockStart, blockEnd).split("\n")
    val targetPrefix = "#".repeat(level) + " "

    val allHaveHeading = lines.all { Regex("^#{1,6} ").find(it)?.value == targetPrefix }

    val newLines = if (allHaveHeading) {
        lines.map { stripLinePrefix(it) }
    } else {
        lines.map { targetPrefix + stripLinePrefix(it) }
    }

    val newBlockText = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlockText + text.substring(blockEnd)
    return value.copy(text = newText, selection = computeBlockSelection(text, selection, blockStart, lines, newLines, newBlockText))
}

fun isInlineFormatActive(text: String, selection: TextRange, marker: String): Boolean {
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val lines = text.substring(blockStart, blockEnd).split("\n").filter { it.isNotBlank() }
    return lines.isNotEmpty() && lines.all { hasInlineMarker(it.substring(getLinePrefix(it).length), marker) }
}

fun isLinePrefixActive(text: String, selection: TextRange, prefix: String): Boolean {
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    return text.substring(blockStart, blockEnd).split("\n").all { matchesPrefix(it, prefix) }
}

fun getActiveHeadingLevel(text: String, cursorPos: Int): Int? {
    val lineStart = text.lastIndexOf('\n', (cursorPos - 1).coerceAtLeast(0)) + 1
    val lineEnd = text.indexOf('\n', cursorPos).let { if (it == -1) text.length else it }
    val match = Regex("^(#{1,6}) ").find(text.substring(lineStart, lineEnd)) ?: return null
    return match.groupValues[1].length
}

fun insertCodeBlock(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val selection = value.selection
    if (selection.collapsed) {
        val insert = "```\n\n```"
        val newText = text.substring(0, selection.start) + insert + text.substring(selection.start)
        return value.copy(text = newText, selection = TextRange(selection.start + 4))
    }
    val selectedText = text.substring(selection.start, selection.end)
    val newText = text.substring(0, selection.start) + "```\n" + selectedText + "\n```" + text.substring(selection.end)
    return value.copy(text = newText, selection = TextRange(selection.start + 4, selection.start + 4 + selectedText.length))
}

fun insertHorizontalRule(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start
    val insert = "\n---\n"
    val newText = text.substring(0, cursor) + insert + text.substring(cursor)
    return value.copy(text = newText, selection = TextRange(cursor + insert.length))
}

fun insertLink(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val selection = value.selection
    if (selection.collapsed) {
        val insert = "[link](url)"
        val newText = text.substring(0, selection.start) + insert + text.substring(selection.start)
        return value.copy(text = newText, selection = TextRange(selection.start + 1, selection.start + 5))
    }
    val selectedText = text.substring(selection.start, selection.end)
    val newText = text.substring(0, selection.start) + "[" + selectedText + "](url)" + text.substring(selection.end)
    return value.copy(text = newText, selection = TextRange(selection.end + 3, selection.end + 6))
}

private val checkboxPattern = Regex("^(\\s*- )\\[([ xX])] ")

fun tryToggleCheckbox(offset: Int, value: TextFieldValue): TextFieldValue? {
    val text = value.text
    val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
    val lineEnd = text.indexOf('\n', offset).let { if (it == -1) text.length else it }
    val match = checkboxPattern.find(text.substring(lineStart, lineEnd)) ?: return null
    val bracketOffset = lineStart + match.groups[1]!!.value.length
    if (offset > bracketOffset + 3) return null
    val newChar = if (match.groups[2]!!.value.lowercase() == "x") " " else "x"
    val newText = text.substring(0, bracketOffset + 1) + newChar + text.substring(bracketOffset + 2)
    return value.copy(text = newText, selection = TextRange(offset))
}

fun findCheckboxPositions(text: String): List<Pair<Int, Boolean>> =
    Regex("(?m)^(\\s*- )\\[([ xX])] ").findAll(text).map { match ->
        val bracketOffset = match.groups[1]!!.range.last + 1
        val isChecked = match.groups[2]!!.value.lowercase() == "x"
        bracketOffset to isChecked
    }.toList()

// ---------------------------------------------------------------------------
// Markdown -> HTML conversion (for HTML consumers such as email).
// ---------------------------------------------------------------------------

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun inlineMarkdownToHtml(s: String): String {
    var t = escapeHtml(s)
    t = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").replace(t) { m -> "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>" }
    t = Regex("\\*\\*(.+?)\\*\\*").replace(t) { "<b>${it.groupValues[1]}</b>" }
    t = Regex("__(.+?)__").replace(t) { "<b>${it.groupValues[1]}</b>" }
    t = Regex("~~(.+?)~~").replace(t) { "<s>${it.groupValues[1]}</s>" }
    t = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)").replace(t) { "<i>${it.groupValues[1]}</i>" }
    t = Regex("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)").replace(t) { "<i>${it.groupValues[1]}</i>" }
    t = Regex("`([^`]+)`").replace(t) { "<code>${it.groupValues[1]}</code>" }
    return t
}

/**
 * Converts the markdown produced by [MarkdownEditor] into HTML suitable for an
 * email body. Handles headings, bold/italic/strikethrough/inline-code, links,
 * bullet/numbered lists, checkboxes, blockquotes, fenced code blocks and rules.
 */
fun markdownToHtml(md: String): String {
    val lines = md.split("\n")
    val sb = StringBuilder()
    var inUl = false
    var inOl = false

    fun closeLists() {
        if (inUl) { sb.append("</ul>"); inUl = false }
        if (inOl) { sb.append("</ol>"); inOl = false }
    }

    val headingRe = Regex("^(#{1,6})\\s+(.*)$")
    val checkboxRe = Regex("^\\s*- \\[([ xX])]\\s+(.*)$")
    val bulletRe = Regex("^\\s*[-*+]\\s+(.*)$")
    val numberedRe = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    val quoteRe = Regex("^>\\s?(.*)$")

    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        val trimmed = line.trimStart()

        val heading = headingRe.matchEntire(line)
        val checkbox = checkboxRe.matchEntire(line)
        val bullet = if (checkbox == null) bulletRe.matchEntire(line) else null
        val numbered = numberedRe.matchEntire(line)
        val quote = quoteRe.matchEntire(line)

        when {
            trimmed.startsWith("```") -> {
                closeLists()
                sb.append("<pre><code>")
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    sb.append(escapeHtml(lines[i])).append("\n")
                    i++
                }
                sb.append("</code></pre>")
                i++ // skip closing fence (if present)
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                closeLists(); sb.append("<hr>"); i++
            }
            heading != null -> {
                closeLists()
                val level = heading.groupValues[1].length
                sb.append("<h$level>").append(inlineMarkdownToHtml(heading.groupValues[2])).append("</h$level>")
                i++
            }
            checkbox != null -> {
                if (inOl) { sb.append("</ol>"); inOl = false }
                if (!inUl) { sb.append("<ul>"); inUl = true }
                val checked = checkbox.groupValues[1].lowercase() == "x"
                sb.append("<li><input type=\"checkbox\" disabled")
                if (checked) sb.append(" checked")
                sb.append("> ").append(inlineMarkdownToHtml(checkbox.groupValues[2])).append("</li>")
                i++
            }
            bullet != null -> {
                if (inOl) { sb.append("</ol>"); inOl = false }
                if (!inUl) { sb.append("<ul>"); inUl = true }
                sb.append("<li>").append(inlineMarkdownToHtml(bullet.groupValues[1])).append("</li>")
                i++
            }
            numbered != null -> {
                if (inUl) { sb.append("</ul>"); inUl = false }
                if (!inOl) { sb.append("<ol>"); inOl = true }
                sb.append("<li>").append(inlineMarkdownToHtml(numbered.groupValues[1])).append("</li>")
                i++
            }
            quote != null -> {
                closeLists()
                sb.append("<blockquote>").append(inlineMarkdownToHtml(quote.groupValues[1])).append("</blockquote>")
                i++
            }
            line.isBlank() -> {
                closeLists(); sb.append("<br>"); i++
            }
            else -> {
                closeLists()
                sb.append("<p>").append(inlineMarkdownToHtml(line)).append("</p>")
                i++
            }
        }
    }
    closeLists()
    return "<html><body>$sb</body></html>"
}
