package com.vayunmathur.notes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.closeCachedDatabase
import com.vayunmathur.library.util.onFileDrop
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteDao
import com.vayunmathur.notes.data.NoteDatabase
import com.vayunmathur.notes.ui.NotePage
import com.vayunmathur.notes.ui.NotesListPage
import com.vayunmathur.notes.util.NotesViewModel
import com.vayunmathur.notes.util.NotesViewModelFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var noteDao: NoteDao
    private val notesViewModel: NotesViewModel by viewModels {
        NotesViewModelFactory(application, noteDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Migrate notes from old database (used default name "passwords-db") to "notes-db"
        val oldDbFile = getDatabasePath("passwords-db")
        var oldNotes = emptyList<Note>()
        if (oldDbFile.exists()) {
            try {
                val oldDb = buildDatabase<NoteDatabase>()
                oldNotes = runBlocking { oldDb.noteDao().getAll() }
                closeCachedDatabase<NoteDatabase>()
            } catch (_: Exception) { }
        }

        val db = buildDatabase<NoteDatabase>(dbName = "notes-db")
        noteDao = db.noteDao()

        if (oldNotes.isNotEmpty()) {
            runBlocking { noteDao.upsertAll(oldNotes) }
        }
        if (oldDbFile.exists()) {
            oldDbFile.delete()
            File("${oldDbFile.path}-wal").delete()
            File("${oldDbFile.path}-shm").delete()
            File("${oldDbFile.path}-journal").delete()
        }

        handleIntent(intent)

        setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize().onFileDrop { uris ->
                    notesViewModel.importFiles(uris)
                }) {
                    Navigation(notesViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val uris = IntentHelper.getUrisFromIntent(it)
            if (uris.isNotEmpty()) {
                notesViewModel.importFiles(uris)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object NotesList: Route
    @Serializable
    data class Note(val id: Long): Route
}

@Composable
fun Navigation(notesViewModel: NotesViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.NotesList)
    MainNavigation(backStack) {
        entry<Route.NotesList>(metadata = ListPage()) {
            NotesListPage(backStack, notesViewModel)
        }
        entry<Route.Note>(metadata = ListDetailPage()) {
            NotePage(backStack, notesViewModel, it.id)
        }
    }
}
