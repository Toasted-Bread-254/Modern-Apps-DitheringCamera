package com.vayunmathur.notes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.IntegrationInstructions
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.findCheckboxPositions
import com.vayunmathur.library.ui.getActiveHeadingLevel
import com.vayunmathur.library.ui.insertCodeBlock
import com.vayunmathur.library.ui.insertHorizontalRule
import com.vayunmathur.library.ui.insertHeading
import com.vayunmathur.library.ui.insertLink
import com.vayunmathur.library.ui.isInlineFormatActive
import com.vayunmathur.library.ui.isLinePrefixActive
import com.vayunmathur.library.ui.toggleInlineFormat
import com.vayunmathur.library.ui.toggleLinePrefix
import com.vayunmathur.library.ui.tryToggleCheckbox
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.R as LibraryR
import com.vayunmathur.notes.R
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.util.NotesViewModel

// Markdown editing helpers now live in the shared :library:ui module
// (com.vayunmathur.library.ui.MarkdownEditor) so notes and email share them.

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotePage(
    backStack: NavBackStack<Route>,
    notesViewModel: NotesViewModel,
    noteID: Long,
) {
    var note by notesViewModel.editableNote(noteID) { Note(0, "", "") }

    if (noteID != 0L && note.id == 0L) return

    var showSearchBar by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var searchIndex by remember { mutableIntStateOf(0) }
    val focusRequestor = remember { FocusRequester() }

    val searchResultsCount by remember(note.content, searchText) {
        derivedStateOf {
            notesViewModel.searchResultsCount(note.content, searchText)
        }
    }

    BackHandler(enabled = showSearchBar) {
        if (searchText.isNotEmpty()) {
            searchText = ""
            searchIndex = 0
        } else {
            showSearchBar = false
        }
    }

    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            focusRequestor.requestFocus()
        }
    }

    val context = LocalContext.current

    LaunchedEffect(notesViewModel) {
        notesViewModel.shareUris.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Note"))
        }
    }

    var contentValue by remember(noteID) {
        mutableStateOf(TextFieldValue(notesViewModel.parseDisplay(note.content)))
    }

    var showHeadingMenu by remember { mutableStateOf(false) }

    fun applyFormat(transform: (TextFieldValue) -> TextFieldValue) {
        contentValue = transform(contentValue)
        note = note.copy(content = contentValue.text)
        contentValue = contentValue.copy(annotatedString = notesViewModel.parseDisplay(contentValue.text))
    }

    Scaffold(topBar = {
        TopAppBar(title = {
            if (showSearchBar) {
                TextField(
                    value = searchText,
                    onValueChange = {
                        searchText = it
                        searchIndex = 0
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequestor),
                    placeholder = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    )
                )
            }
        }, navigationIcon = {
            Row {
                IconNavigation {
                    if (showSearchBar) {
                        showSearchBar = false
                    } else {
                        backStack.pop()
                    }
                }
            }
        }, actions = {
            if (!showSearchBar) {
                IconButton({ showSearchBar = true }) { IconSearch() }
                IconButton({
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("note", note.content))
                }) {
                    IconCopy()
                }
                IconButton({
                    notesViewModel.requestShare(note.title, note.content)
                }) {
                    IconShare()
                }
                IconButton(onClick = {
                    notesViewModel.delete(note)
                    backStack.pop()
                }) {
                    IconDelete()
                }
            } else {
                if (searchResultsCount > 0) {
                    Text(
                        "${searchIndex + 1} / $searchResultsCount",
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            }
        })
    }, floatingActionButton = {
        if (showSearchBar) {
            Column(Modifier.imePadding()) {
                SmallFloatingActionButton({ if (searchIndex > 0) searchIndex-- }) {
                    Icon(painterResource(LibraryR.drawable.chevron_right_24px), null, modifier = Modifier.rotate(-90f))
                }
                SmallFloatingActionButton({
                    if (searchIndex < searchResultsCount - 1) searchIndex++
                }) {
                    Icon(painterResource(LibraryR.drawable.chevron_right_24px), null, modifier = Modifier.rotate(90f))
                }
            }
        }
    }, bottomBar = {
        if (!showSearchBar) {
            Surface(
                modifier = Modifier.imePadding(),
                tonalElevation = 3.dp,
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val isBoldActive = isInlineFormatActive(contentValue.text, contentValue.selection, "**")
                    val isItalicActive = isInlineFormatActive(contentValue.text, contentValue.selection, "*")
                    val isStrikethroughActive = isInlineFormatActive(contentValue.text, contentValue.selection, "~~")
                    val isCodeActive = isInlineFormatActive(contentValue.text, contentValue.selection, "`")
                    val isQuoteActive = isLinePrefixActive(contentValue.text, contentValue.selection, "> ")
                    val isBulletActive = isLinePrefixActive(contentValue.text, contentValue.selection, "- ")
                    val isNumberedActive = isLinePrefixActive(contentValue.text, contentValue.selection, "1. ")
                    val isCheckboxActive = isLinePrefixActive(contentValue.text, contentValue.selection, "- [ ] ")
                    val activeHeadingLevel = getActiveHeadingLevel(contentValue.text, contentValue.selection.start)
                    val activeBg = Modifier.background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(8.dp)
                    )

                    IconButton(
                        onClick = { applyFormat { toggleInlineFormat(it, "**") } },
                        modifier = if (isBoldActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatBold, "Bold") }

                    IconButton(
                        onClick = { applyFormat { toggleInlineFormat(it, "*") } },
                        modifier = if (isItalicActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatItalic, "Italic") }

                    IconButton(
                        onClick = { applyFormat { toggleInlineFormat(it, "~~") } },
                        modifier = if (isStrikethroughActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatStrikethrough, "Strikethrough") }

                    IconButton(
                        onClick = { applyFormat { toggleInlineFormat(it, "`") } },
                        modifier = if (isCodeActive) activeBg else Modifier
                    ) { Icon(Icons.Default.Code, "Inline Code") }

                    Box {
                        IconButton(
                            onClick = { showHeadingMenu = true },
                            modifier = if (activeHeadingLevel != null) activeBg else Modifier
                        ) {
                            Icon(Icons.Default.Title, "Heading")
                        }
                        DropdownMenu(
                            expanded = showHeadingMenu,
                            onDismissRequest = { showHeadingMenu = false }
                        ) {
                            (1..6).forEach { level ->
                                val isActive = activeHeadingLevel == level
                                DropdownMenuItem(
                                    text = { Text("H$level") },
                                    onClick = {
                                        applyFormat { insertHeading(it, level) }
                                        showHeadingMenu = false
                                    },
                                    modifier = if (isActive) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
                                    trailingIcon = if (isActive) {{ Icon(Icons.Default.Check, "Active") }} else null
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { applyFormat { toggleLinePrefix(it, "> ") } },
                        modifier = if (isQuoteActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatQuote, "Quote") }

                    IconButton(
                        onClick = { applyFormat { toggleLinePrefix(it, "- ") } },
                        modifier = if (isBulletActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatListBulleted, "Bullet List") }

                    IconButton(
                        onClick = { applyFormat { toggleLinePrefix(it, "1. ") } },
                        modifier = if (isNumberedActive) activeBg else Modifier
                    ) { Icon(Icons.Default.FormatListNumbered, "Numbered List") }

                    IconButton(
                        onClick = { applyFormat { toggleLinePrefix(it, "- [ ] ") } },
                        modifier = if (isCheckboxActive) activeBg else Modifier
                    ) { Icon(Icons.Default.CheckBox, "Checkbox") }

                    IconButton({ applyFormat { insertCodeBlock(it) } }) {
                        Icon(Icons.Default.IntegrationInstructions, "Code Block")
                    }

                    IconButton({ applyFormat { insertHorizontalRule(it) } }) {
                        Icon(Icons.Default.HorizontalRule, "Horizontal Rule")
                    }

                    IconButton({ applyFormat { insertLink(it) } }) {
                        Icon(Icons.Default.Link, "Link")
                    }
                }
            }
        }
    }) { paddingValues ->
        LazyColumn(contentPadding = paddingValues + PaddingValues(horizontal = 16.dp) + PaddingValues(bottom = 16.dp)) {
            item {
                BasicTextField(
                    note.title,
                    { note = note.copy(title = it) },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(LocalContentColor.current),
                    decorationBox = { innerTextField ->
                        Box {
                            if (note.title.isEmpty()) Text(
                                text = stringResource(R.string.title),
                                style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            innerTextField()
                        }
                    }
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
            }
            item {
                val displayValue by remember(note.content, searchText, searchIndex, showSearchBar, contentValue.selection) {
                    derivedStateOf {
                        if (showSearchBar) {
                            TextFieldValue(
                                notesViewModel.parseDisplay(
                                    note.content,
                                    searchQuery = searchText,
                                    searchIndex = searchIndex,
                                ),
                                selection = contentValue.selection,
                            )
                        } else {
                            contentValue
                        }
                    }
                }
                var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                val density = LocalDensity.current
                Box {
                    BasicTextField(
                        displayValue,
                        { newValue ->
                            if (newValue.text == contentValue.text && newValue.selection.collapsed) {
                                tryToggleCheckbox(newValue.selection.start, contentValue)?.let {
                                    applyFormat { _ -> it }
                                    return@BasicTextField
                                }
                            }
                            applyFormat { _ -> newValue }
                        },
                        Modifier.fillMaxSize(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current),
                        cursorBrush = SolidColor(LocalContentColor.current),
                        onTextLayout = { textLayoutResult = it },
                        decorationBox = { innerTextField ->
                            Box {
                                if (note.content.isEmpty()) Text(
                                    text = stringResource(R.string.content),
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                innerTextField()
                            }
                        }
                    )
                    textLayoutResult?.let { layout ->
                        val checkboxes = remember(note.content) { findCheckboxPositions(note.content) }
                        checkboxes.forEach { (bracketOffset, isChecked) ->
                            val rect = runCatching { layout.getBoundingBox(bracketOffset) }.getOrNull() ?: return@forEach
                            val lineHeight = rect.bottom - rect.top
                            with(density) {
                                Icon(
                                    imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = if (isChecked) "Checked" else "Unchecked",
                                    tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .offset(x = rect.left.toDp() - 12.dp, y = rect.top.toDp())
                                        .size(lineHeight.toDp())
                                        .clickable {
                                            tryToggleCheckbox(bracketOffset, contentValue)?.let {
                                                applyFormat { _ -> it }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
