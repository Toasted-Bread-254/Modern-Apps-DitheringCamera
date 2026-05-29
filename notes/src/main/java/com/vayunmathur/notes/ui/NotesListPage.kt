package com.vayunmathur.notes.ui

import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.ListPageR
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.util.NotesViewModel

@Composable
fun NotesListPage(backStack: NavBackStack<Route>, viewModel: NotesViewModel) {
    val context = LocalContext.current
    val notes by viewModel.notes.collectAsState()

    ListPageR<Note, Route, Route.Note>(
        backStack = backStack,
        data = notes,
        onReorder = viewModel::upsertAll,
        title = "Notes",
        headlineContent = {
            Text(it.title)
        },
        supportingContent = {
            Text(it.content.substringBefore('\n').take(40))
        },
        viewPage = { Route.Note(it) },
        editPage = { Route.Note(0) },
        searchEnabled = true,
        otherActions = {
            val pass = remember { DatabaseHelper(context).getPassphrase() }
            BackupButtons(
                dbConfigs = listOf("passwords-db" to pass),
                extraFiles = emptyList()
            )
        },
        selectionActions = { selectedNotes, clearSelection ->
            IconButton(onClick = {
                selectedNotes.forEach { viewModel.delete(it) }
                clearSelection()
            }) {
                IconDelete()
            }
        }
    )
}
