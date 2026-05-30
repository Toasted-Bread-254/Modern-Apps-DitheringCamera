package com.vayunmathur.messages.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.messages.data.MessagesDatabase
import kotlinx.coroutines.launch

/**
 * Thin Android-aware ViewModel that:
 *  1. Starts the foreground service on first composition (so the
 *     puppets begin loading the moment the user opens the app, not
 *     just when they enter a setup screen).
 *  2. Exposes a single suspending [send] for the UI to call.
 *
 * All actual state lives in [MessagesSessionManager] (singleton). This
 * ViewModel exists mostly so Compose has a viewModel() entry point per
 * Activity and to keep the auto-start logic out of MainActivity's setContent.
 */
class MessagesViewModel(application: Application) : AndroidViewModel(application) {

    init {
        // Idempotent: the session manager / service both no-op if already running.
        MessagesSessionManager.init(application)
        MessagesService.start(application)
    }

    val connectionStates = MessagesSessionManager.connectionStates

    fun database(): MessagesDatabase = MessagesSessionManager.database()

    fun send(conversationId: String, body: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = MessagesSessionManager.sendMessage(conversationId, body)
            onResult(ok)
        }
    }

    /**
     * Send an image (or other supported media) on [conversationId].
     * The UI reads the URI into a byte array on the IO dispatcher (so
     * we don't block the main thread on large attachments); the actual
     * upload + send happens in the session manager.
     */
    fun sendMedia(
        conversationId: String,
        uri: android.net.Uri,
        caption: String?,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val (bytes, mime, fileName) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val cr = getApplication<Application>().contentResolver
                val data = cr.openInputStream(uri)?.use { it.readBytes() }
                val type = cr.getType(uri) ?: "application/octet-stream"
                val name = uri.lastPathSegment ?: "attachment"
                Triple(data, type, name)
            }
            if (bytes == null) {
                onResult(false)
                return@launch
            }
            val ok = MessagesSessionManager.sendMedia(
                conversationId = conversationId,
                bytes = bytes,
                mime = mime,
                fileName = fileName,
                caption = caption,
            )
            onResult(ok)
        }
    }

    fun sendReaction(messageId: String, emoji: String, action: ReactionAction) {
        viewModelScope.launch {
            MessagesSessionManager.sendReaction(messageId, emoji, action)
        }
    }

    fun deleteConversation(conversationId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(MessagesSessionManager.deleteConversation(conversationId))
        }
    }

    fun sendTyping(conversationId: String) {
        viewModelScope.launch {
            MessagesSessionManager.sendTyping(conversationId)
        }
    }

    fun fetchMessages(conversationId: String) {
        MessagesSessionManager.fetchMessages(conversationId)
    }

    fun markRead(conversationId: String) {
        viewModelScope.launch {
            MessagesSessionManager.markRead(conversationId)
        }
    }

    fun forceResync() {
        MessagesSessionManager.forceResync()
    }
}
