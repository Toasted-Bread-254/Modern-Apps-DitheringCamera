package com.vayunmathur.messages.util

import android.content.Context
import android.util.Log
import com.vayunmathur.messages.data.Conversation
import com.vayunmathur.messages.data.Message
import com.vayunmathur.messages.data.MessageDirection
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.data.MessageState
import com.vayunmathur.messages.data.MessagesDatabase
import com.vayunmathur.messages.data.buildMessagesDatabase
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.gmessages.GMessagesClient
import com.vayunmathur.messages.gvoice.GVoiceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges [GMessagesClient] + [GVoiceClient]'s state + event streams
 * into the same Room writes and notification triggers.
 *
 * [connectionStates] is the per-source unified state map (see
 * [SourceConnectionState]) and [incoming] is the new-message fanout for
 * the notification path. Adding a new source = subscribing to its state
 * + event flow here; no consumer needs to change.
 */
object MessagesSessionManager {

    private const val TAG = "MessagesSession"

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private lateinit var db: MessagesDatabase

    /** Per-source unified connection state. */
    private val _connectionStates = MutableStateFlow<Map<MessageSource, SourceConnectionState>>(
        mapOf(
            MessageSource.MESSAGES_WEB to SourceConnectionState.Idle,
            MessageSource.VOICE to SourceConnectionState.Idle,
        )
    )
    val connectionStates: StateFlow<Map<MessageSource, SourceConnectionState>> =
        _connectionStates.asStateFlow()

    /** Stream of "you just got a new message" events for the service to
     *  turn into MessagingStyle notifications. */
    private val _incoming = MutableSharedFlow<GMEvent.IncomingMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<GMEvent.IncomingMessage> = _incoming.asSharedFlow()

    private val collectorJobs = mutableListOf<Job>()

    /** Don't fire incoming-message notifications during the initial scan. */
    private val backfillComplete = mutableMapOf(
        MessageSource.MESSAGES_WEB to false,
        MessageSource.VOICE to false,
    )

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        db = buildMessagesDatabase(appContext)
        GMessagesClient.init(appContext)
        GVoiceClient.init(appContext)
        Log.i(TAG, "init")
        wireCollectors()
    }

    fun database(): MessagesDatabase = db

    fun start() {
        if (!initialized.get()) return
        GMessagesClient.start()
        GVoiceClient.start()
    }

    fun stop() {
        GMessagesClient.stop()
        GVoiceClient.stop()
        backfillComplete[MessageSource.MESSAGES_WEB] = false
        backfillComplete[MessageSource.VOICE] = false
    }

    /** Stop one source independently — used from the per-source
     *  Disconnect button in Settings. */
    fun stop(source: MessageSource) {
        when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.stop()
            MessageSource.VOICE -> GVoiceClient.stop()
        }
        backfillComplete[source] = false
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        val source = sourceFor(conversationId) ?: return false
        // Insert PENDING row immediately so the UI updates.
        val pendingId = "${source.idPrefix}:pending:${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        db.messageDao().upsert(
            Message(
                id = pendingId,
                conversationId = conversationId,
                body = body,
                direction = MessageDirection.OUTGOING,
                state = MessageState.PENDING,
                timestamp = now,
                senderName = null,
            )
        )
        val ok = when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.sendMessage(conversationId, body)
            MessageSource.VOICE -> GVoiceClient.sendMessage(conversationId, body)
        }
        db.messageDao().updateState(
            pendingId,
            if (ok) MessageState.SENT else MessageState.FAILED,
        )
        return ok
    }

    /**
     * Send an image (or other supported media) on [conversationId].
     * Inserts a PENDING row carrying [caption] (or "[Image]" if blank)
     * so the UI gets immediate feedback, then routes the actual upload
     * to the per-source client.
     */
    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String,
        caption: String?,
    ): Boolean {
        val source = sourceFor(conversationId) ?: return false
        val previewBody = caption?.takeIf { it.isNotBlank() } ?: "[Image]"
        val pendingId = "${source.idPrefix}:pending:${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        db.messageDao().upsert(
            Message(
                id = pendingId,
                conversationId = conversationId,
                body = previewBody,
                direction = MessageDirection.OUTGOING,
                state = MessageState.PENDING,
                timestamp = now,
                senderName = null,
            )
        )
        val ok = when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.sendMedia(
                conversationId = conversationId,
                data = bytes,
                mime = mime,
                fileName = fileName,
                caption = caption,
            )
            MessageSource.VOICE -> GVoiceClient.sendMedia(
                conversationId = conversationId,
                data = bytes,
                mime = mime,
                caption = caption,
            )
        }
        db.messageDao().updateState(
            pendingId,
            if (ok) MessageState.SENT else MessageState.FAILED,
        )
        return ok
    }

    suspend fun markRead(conversationId: String) {
        // Always update the local row immediately so the unread badge clears.
        db.conversationDao().markRead(conversationId)
        // Then propagate to the server. Failures are non-fatal — the
        // local state already reflects "user has seen this".
        val source = sourceFor(conversationId) ?: return
        when (source) {
            MessageSource.MESSAGES_WEB -> {
                // gmessages MarkRead is per-message; pick the newest
                // message in the thread (same convention Messages-for-
                // Web uses when the user opens a chat).
                val latest = db.messageDao().observeForConversation(conversationId)
                    .firstOrNull()
                    ?.maxByOrNull { it.timestamp }
                val webMsgId = latest?.id?.substringAfter(':', latest.id)
                GMessagesClient.markRead(conversationId, webMsgId)
            }
            MessageSource.VOICE -> {
                GVoiceClient.markRead(conversationId)
            }
        }
    }

    /**
     * Delete [conversationId] on the server. The local row is removed
     * (and cascades clear its messages) once the per-source client
     * confirms — partial deletes leave the row in place so the user
     * can retry from the UI.
     */
    suspend fun deleteConversation(conversationId: String): Boolean {
        val source = sourceFor(conversationId) ?: return false
        val existing = db.conversationDao().get(conversationId)
        val ok = when (source) {
            MessageSource.MESSAGES_WEB -> {
                GMessagesClient.deleteConversation(conversationId, existing?.peerPhoneE164)
            }
            MessageSource.VOICE -> {
                GVoiceClient.deleteThread(conversationId)
            }
        }
        if (ok) db.conversationDao().deleteById(conversationId)
        return ok
    }

    /**
     * Add/remove/switch a reaction on a message. Only Google Messages
     * supports reactions — Voice ignores the call and returns false.
     */
    suspend fun sendReaction(messageId: String, emoji: String, action: ReactionAction): Boolean {
        val msg = db.messageDao().get(messageId) ?: return false
        val source = sourceFor(msg.conversationId) ?: return false
        return when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.sendReaction(
                messageId = messageId,
                emoji = emoji,
                action = when (action) {
                    ReactionAction.ADD ->
                        client.Client.SendReactionRequest.Action.ADD
                    ReactionAction.REMOVE ->
                        client.Client.SendReactionRequest.Action.REMOVE
                    ReactionAction.SWITCH ->
                        client.Client.SendReactionRequest.Action.SWITCH
                },
            )
            MessageSource.VOICE -> false
        }
    }

    /**
     * Notify the peer that the local user is typing.
     * Only Google Messages exposes a typing endpoint; Voice has none.
     */
    suspend fun sendTyping(conversationId: String): Boolean {
        val source = sourceFor(conversationId) ?: return false
        return when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.sendTyping(conversationId)
            MessageSource.VOICE -> false
        }
    }

    /**
     * Bulk-write a backfill batch of messages in ONE transaction.
     * Called by the protocol clients when LIST_MESSAGES / GetThread
     * returns — saves dozens of separate Flow notifications when
     * populating a thread.
     */
    suspend fun bulkUpsertMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        db.messageDao().upsertAll(messages)
    }

    fun forceResync() {
        GMessagesClient.forceResync()
        GVoiceClient.forceResync()
    }

    fun fetchMessages(conversationId: String) {
        when (sourceFor(conversationId)) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.fetchMessages(conversationId)
            MessageSource.VOICE -> GVoiceClient.fetchMessages(conversationId)
            null -> Unit
        }
    }

    private fun wireCollectors() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()

        collectorJobs += scope.launch {
            GMessagesClient.state.collect { s ->
                _connectionStates.value =
                    _connectionStates.value + (MessageSource.MESSAGES_WEB to s.toUnified())
            }
        }
        collectorJobs += scope.launch {
            GVoiceClient.state.collect { s ->
                _connectionStates.value =
                    _connectionStates.value + (MessageSource.VOICE to s.toUnified())
            }
        }
        collectorJobs += scope.launch {
            GMessagesClient.events.collect { handleEvent(it) }
        }
        collectorJobs += scope.launch {
            GVoiceClient.events.collect { handleEvent(it) }
        }
    }

    private suspend fun handleEvent(event: GMEvent) {
        when (event) {
            is GMEvent.ConversationUpdate -> {
                val id = "${event.source.idPrefix}:${event.conversationId}"
                val existing = db.conversationDao().get(id)
                val merged = Conversation(
                    id = id,
                    source = event.source,
                    peerName = event.peerName ?: existing?.peerName,
                    peerPhoneE164 = event.peerPhone ?: existing?.peerPhoneE164,
                    avatarUrl = event.avatarUrl ?: existing?.avatarUrl,
                    lastMessagePreview = event.lastPreview ?: existing?.lastMessagePreview,
                    lastMessageTimestamp = maxOf(event.lastTimestamp, existing?.lastMessageTimestamp ?: 0L),
                    unreadCount = event.unreadCount,
                    isGroup = event.isGroup,
                    participantCount = event.participantCount,
                    conversationType = event.conversationType,
                    outgoingId = event.outgoingId ?: existing?.outgoingId,
                )
                db.conversationDao().upsert(merged)
                backfillComplete[event.source] = true
            }
            is GMEvent.MessageUpdate -> {
                val convId = "${event.source.idPrefix}:${event.conversationId}"
                val msgId = "${event.source.idPrefix}:${event.messageId}"
                db.messageDao().upsert(
                    Message(
                        id = msgId,
                        conversationId = convId,
                        body = event.body,
                        direction = if (event.outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                        state = if (event.outgoing) MessageState.SENT else MessageState.DELIVERED,
                        timestamp = event.timestamp,
                        senderName = event.senderName,
                        reactionsJson = event.reactionsJson,
                    )
                )
            }
            is GMEvent.IncomingMessage -> {
                if (backfillComplete[event.source] == true) {
                    _incoming.tryEmit(event)
                }
                val convId = "${event.source.idPrefix}:${event.conversationId}"
                val msgId = "${event.source.idPrefix}:${event.messageId}"
                db.messageDao().upsert(
                    Message(
                        id = msgId,
                        conversationId = convId,
                        body = event.body,
                        direction = MessageDirection.INCOMING,
                        state = MessageState.DELIVERED,
                        timestamp = event.timestamp,
                        senderName = event.peerName,
                    )
                )
            }
            is GMEvent.MessageDeleted -> {
                db.messageDao().deleteById("${event.source.idPrefix}:${event.messageId}")
            }
            is GMEvent.ConversationDeleted -> {
                db.conversationDao().deleteById("${event.source.idPrefix}:${event.conversationId}")
            }
        }
    }

    private fun sourceFor(conversationId: String): MessageSource? = when {
        conversationId.startsWith("${MessageSource.MESSAGES_WEB.idPrefix}:") -> MessageSource.MESSAGES_WEB
        conversationId.startsWith("${MessageSource.VOICE.idPrefix}:") -> MessageSource.VOICE
        else -> null
    }
}

/** Action passed to [MessagesSessionManager.sendReaction]. Mirrors the
 *  three-state add/remove/switch enum in [SendReactionRequest.Action]
 *  without exposing the protobuf type to non-protocol callers. */
enum class ReactionAction { ADD, REMOVE, SWITCH }
