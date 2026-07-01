package com.vayunmathur.messages.util

import com.vayunmathur.messages.data.Conversation
import org.json.JSONObject

/**
 * Message-request flag helpers. The flag lives inside the conversation's
 * [Conversation.serviceData] JSON (key "isMessageRequest") so no Room
 * schema migration is needed. Different sources populate it differently
 * (Signal writes serviceData directly; Meta/Instagram emit a separate
 * MessageRequestReceived event) — these helpers give one read/write path.
 */
private const val MESSAGE_REQUEST_KEY = "isMessageRequest"

/** True iff this conversation is an unaccepted message request. */
fun Conversation.isMessageRequest(): Boolean =
    serviceDataFlag(serviceData, MESSAGE_REQUEST_KEY)

internal fun serviceDataFlag(serviceData: String?, key: String): Boolean {
    if (serviceData.isNullOrBlank()) return false
    return runCatching { JSONObject(serviceData).optBoolean(key, false) }
        .getOrDefault(false)
}

/** Return [serviceData] JSON with [key] set to [value], preserving other keys. */
internal fun withServiceDataFlag(serviceData: String?, key: String, value: Boolean): String {
    val obj = runCatching {
        if (serviceData.isNullOrBlank()) JSONObject() else JSONObject(serviceData)
    }.getOrDefault(JSONObject())
    obj.put(key, value)
    return obj.toString()
}

internal fun withMessageRequestFlag(serviceData: String?, value: Boolean): String =
    withServiceDataFlag(serviceData, MESSAGE_REQUEST_KEY, value)
