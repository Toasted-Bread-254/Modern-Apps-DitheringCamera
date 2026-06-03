package com.vayunmathur.messages.meta

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Meta (Facebook/Messenger/Instagram) Lightspeed protocol implementation.
 * Handles MQTT messages, task encoding, and protocol specifics.
 * 
 * Based on mautrix-meta/pkg/messagix:
 * - MQTT over WebSocket for real-time messaging
 * - Lightspeed binary protocol for efficient encoding
 * - Cookie-based authentication
 */
object MetaProtocol {
    private const val TAG = "MetaProtocol"

    // MQTT topics (from messagix/topics.go)
    const val TOPIC_LS_REQ = "/ls_req"  // Publish tasks (send message, etc.)
    const val TOPIC_LS_RESP = "/ls_resp"  // Receive responses
    const val TOPIC_LS_APP_SETTINGS = "/ls_app_settings"
    const val TOPIC_TMS = "/t_ms"  // Thread sync
    const val TOPIC_TPI = "/t_p"  // Presence

    // Messenger endpoints
    const val MESSENGER_BASE_URL = "https://www.messenger.com"
    const val MESSENGER_MQTT_URL = "wss://edge-chat.messenger.com/chat"

    // Instagram endpoints
    const val INSTAGRAM_BASE_URL = "https://www.instagram.com"
    const val INSTAGRAM_MQTT_URL = "wss://edge-chat.instagram.com/chat"
    
    // Sync groups (from messagix)
    const val SYNC_GROUP_MESSAGES = 1
    const val SYNC_GROUP_CONTACTS = 2
    const val SYNC_GROUP_THREADS = 3

    /**
     * Lightspeed task for sending a message.
     */
    @Serializable
    data class SendMessageTask(
        val threadId: String,
        val messageId: String,
        val text: String,
        val timestamp: Long,
    )

    /**
     * Lightspeed task for sending a reaction.
     */
    @Serializable
    data class SendReactionTask(
        val threadId: String,
        val messageId: String,
        val reaction: String,
    )

    /**
     * Lightspeed task for marking thread as read.
     */
    @Serializable
    data class ThreadMarkReadTask(
        val threadId: String,
        val lastReadWatermark: Long,
    )

    /**
     * Parsed MQTT message.
     */
    data class MqttMessage(
        val topic: String,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as MqttMessage
            if (topic != other.topic) return false
            if (!payload.contentEquals(other.payload)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = topic.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Parsed message from Meta platform.
     */
    data class MetaMessage(
        val messageId: String,
        val threadId: String,
        val senderId: String,
        val senderName: String?,
        val text: String,
        val timestamp: Long,
        val isGroup: Boolean,
    )

    /**
     * Lightspeed Task - represents an operation to be sent to the server
     * Based on messagix/socket/socket.go Task structure
     */
    @Serializable
    data class LightspeedTask(
        val taskId: Long,
        val taskType: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as LightspeedTask
            if (taskId != other.taskId) return false
            if (taskType != other.taskType) return false
            if (!payload.contentEquals(other.payload)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = taskId.hashCode()
            result = 31 * result + taskType
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Parse incoming MQTT payload to MetaMessage.
     * 
     * Real implementation uses Lightspeed binary protocol:
     * - Payload is a binary-encoded table with rows and columns
     * - Each row represents a message/event
     * - Columns contain message_id, thread_id, sender_id, text, timestamp, etc.
     * 
     * For now, simplified JSON parsing for testing.
     */
    fun parseMessage(payload: ByteArray, platform: MetaAuthData.Platform): MetaMessage? {
        return try {
            val json = String(payload, Charsets.UTF_8)
            val obj = Json.parseToJsonElement(json).jsonObject

            // Extract fields based on platform
            val messageId = obj["message_id"]?.jsonPrimitive?.content ?: return null
            val threadId = obj["thread_id"]?.jsonPrimitive?.content ?: return null
            val senderId = obj["sender_id"]?.jsonPrimitive?.content ?: return null
            val text = obj["text"]?.jsonPrimitive?.content ?: ""
            val timestamp = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: System.currentTimeMillis()

            MetaMessage(
                messageId = messageId,
                threadId = threadId,
                senderId = senderId,
                senderName = obj["sender_name"]?.jsonPrimitive?.content,
                text = text,
                timestamp = timestamp,
                isGroup = obj["is_group"]?.jsonPrimitive?.content?.toBoolean() ?: false
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build Lightspeed payload for sending a message.
     * 
     * Real implementation would:
     * 1. Create a Task with type = SendMessage (e.g., 121)
     * 2. Encode payload as binary table with columns:
     *    - thread_id (string)
     *    - message_id (string) 
     *    - text (string)
     *    - timestamp (long)
     *    - etc.
     * 3. Wrap in MQTT PUBLISH frame to /ls_req topic
     * 
     * For now, simplified JSON for testing.
     */
    fun buildSendMessagePayload(task: SendMessageTask): ByteArray {
        // Lightspeed task structure (simplified)
        // Real format: binary-encoded with task ID, type, and payload table
        val payload = """
            {
                "task_type": 121,
                "thread_id": "${task.threadId}",
                "message_id": "${task.messageId}",
                "text": "${task.text}",
                "timestamp": ${task.timestamp}
            }
        """.trimIndent()
        return payload.toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Build MQTT CONNECT frame for WebSocket
     * Based on MQTT 3.1.1 spec, adapted for Meta's broker
     */
    fun buildMqttConnectFrame(clientId: String, username: String? = null, password: String? = null): ByteArray {
        // Simplified MQTT CONNECT
        // Real implementation needs proper MQTT framing
        val connectPayload = """
            {
                "type": "connect",
                "client_id": "$clientId",
                "username": "${username ?: ""}",
                "keepalive": 60
            }
        """.trimIndent()
        return connectPayload.toByteArray(Charsets.UTF_8)
    }

    /**
     * Extract timestamp from message ID (Meta IDs contain Unix ms).
     */
    fun extractTimestampFromId(messageId: String): Long {
        // Meta message IDs are typically numeric strings containing timestamp
        return try {
            messageId.toLong()
        } catch (e: NumberFormatException) {
            System.currentTimeMillis()
        }
    }
}
