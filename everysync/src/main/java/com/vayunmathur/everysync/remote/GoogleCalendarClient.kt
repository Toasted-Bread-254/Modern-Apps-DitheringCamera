package com.vayunmathur.everysync.remote

import android.util.Log
import com.vayunmathur.everysync.model.RemoteCalendar
import com.vayunmathur.everysync.model.RemoteEvent
import com.vayunmathur.library.network.NetworkClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GoogleEventsResult(val events: List<RemoteEvent>, val nextSyncToken: String?)

/**
 * Google Calendar API client. Lists calendars, then pulls events incrementally
 * via `syncToken` (cancelled events come back with status=cancelled → deletions).
 */
class GoogleCalendarClient(private val accessToken: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun headers() = mapOf("Authorization" to "Bearer $accessToken")

    suspend fun listCalendars(): List<RemoteCalendar> {
        val out = mutableListOf<RemoteCalendar>()
        try {
            val resp = NetworkClient.performRequest(
                "https://www.googleapis.com/calendar/v3/users/me/calendarList", "GET", headers(),
            )
            val root = json.parseToJsonElement(resp.body) as? JsonObject ?: return out
            (root["items"] as? JsonArray)?.forEach { item ->
                val o = item.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentSafe() ?: return@forEach
                out += RemoteCalendar(
                    id = id,
                    displayName = o["summary"]?.jsonPrimitive?.contentSafe() ?: id,
                    color = parseColor(o["backgroundColor"]?.jsonPrimitive?.contentSafe()),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "listCalendars failed", e)
        }
        return out
    }

    suspend fun listEvents(calendarId: String, syncToken: String?): GoogleEventsResult {
        val events = mutableListOf<RemoteEvent>()
        var pageToken: String? = null
        var nextSyncToken: String? = null
        try {
            do {
                val url = buildString {
                    append("https://www.googleapis.com/calendar/v3/calendars/")
                    append(java.net.URLEncoder.encode(calendarId, "UTF-8"))
                    append("/events?maxResults=250&singleEvents=false")
                    if (syncToken != null) append("&syncToken=$syncToken")
                    if (pageToken != null) append("&pageToken=$pageToken")
                }
                val resp = NetworkClient.performRequest(url, "GET", headers())
                val root = json.parseToJsonElement(resp.body) as? JsonObject ?: break
                (root["items"] as? JsonArray)?.forEach { item ->
                    parseEvent(item.jsonObject, calendarId)?.let { events += it }
                }
                pageToken = root["nextPageToken"]?.jsonPrimitive?.contentSafe()
                nextSyncToken = root["nextSyncToken"]?.jsonPrimitive?.contentSafe() ?: nextSyncToken
            } while (pageToken != null)
        } catch (e: Exception) {
            Log.e(TAG, "listEvents failed", e)
        }
        return GoogleEventsResult(events, nextSyncToken)
    }

    private fun parseEvent(o: JsonObject, calendarId: String): RemoteEvent? {
        val id = o["id"]?.jsonPrimitive?.contentSafe() ?: return null
        val status = o["status"]?.jsonPrimitive?.contentSafe()
        if (status == "cancelled") return RemoteEvent(uid = id, calendarId = calendarId, deleted = true)

        val start = o["start"]?.jsonObject
        val end = o["end"]?.jsonObject
        val allDay = start?.get("date") != null
        val startMillis = parseTime(start, allDay)
        val endMillis = parseTime(end, allDay)
        val rrule = (o["recurrence"] as? JsonArray)
            ?.map { it.jsonPrimitive.content }
            ?.firstOrNull { it.startsWith("RRULE") }
            ?.removePrefix("RRULE:")
        return RemoteEvent(
            uid = id,
            etag = o["etag"]?.jsonPrimitive?.contentSafe(),
            calendarId = calendarId,
            summary = o["summary"]?.jsonPrimitive?.contentSafe() ?: "",
            description = o["description"]?.jsonPrimitive?.contentSafe() ?: "",
            location = o["location"]?.jsonPrimitive?.contentSafe() ?: "",
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            timezone = start?.get("timeZone")?.jsonPrimitive?.contentSafe() ?: "UTC",
            rrule = rrule,
        )
    }

    private fun parseTime(node: JsonObject?, allDay: Boolean): Long {
        node ?: return 0L
        return try {
            if (allDay) {
                val d = node["date"]?.jsonPrimitive?.contentSafe() ?: return 0L
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(d)?.time ?: 0L
            } else {
                val dt = node["dateTime"]?.jsonPrimitive?.contentSafe() ?: return 0L
                // RFC 3339, e.g. 2024-01-02T15:04:05-08:00
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                fmt.parse(dt)?.time ?: 0L
            }
        } catch (_: Exception) { 0L }
    }

    private fun parseColor(hex: String?): Int? = try {
        hex?.removePrefix("#")?.take(6)?.let { (0xFF000000.toInt()) or it.toInt(16) }
    } catch (_: Exception) { null }

    /** Create an event from a local edit. Returns the new event id (uid). */
    suspend fun createEvent(calendarId: String, e: RemoteEvent): String? {
        return try {
            val resp = NetworkClient.performRequest(
                "https://www.googleapis.com/calendar/v3/calendars/${java.net.URLEncoder.encode(calendarId, "UTF-8")}/events",
                "POST", headers() + mapOf("Content-Type" to "application/json"), eventBody(e),
            )
            (json.parseToJsonElement(resp.body) as? JsonObject)?.get("id")?.jsonPrimitive?.contentSafe()
        } catch (ex: Exception) {
            Log.e(TAG, "createEvent failed", ex); null
        }
    }

    suspend fun deleteEvent(calendarId: String, eventId: String) {
        try {
            NetworkClient.performRequest(
                "https://www.googleapis.com/calendar/v3/calendars/${java.net.URLEncoder.encode(calendarId, "UTF-8")}/events/${java.net.URLEncoder.encode(eventId, "UTF-8")}",
                "DELETE", headers(),
            )
        } catch (ex: Exception) {
            Log.e(TAG, "deleteEvent failed", ex)
        }
    }

    private fun eventBody(e: RemoteEvent): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val startNode = if (e.allDay) "{\"date\":\"${dateOnly.format(e.startMillis)}\"}" else "{\"dateTime\":\"${iso.format(e.startMillis)}\"}"
        val endNode = if (e.allDay) "{\"date\":\"${dateOnly.format(e.endMillis)}\"}" else "{\"dateTime\":\"${iso.format(e.endMillis)}\"}"
        val sb = StringBuilder("{")
        sb.append("\"summary\":${q(e.summary)}")
        if (e.description.isNotBlank()) sb.append(",\"description\":${q(e.description)}")
        if (e.location.isNotBlank()) sb.append(",\"location\":${q(e.location)}")
        sb.append(",\"start\":$startNode,\"end\":$endNode")
        e.rrule?.takeIf { it.isNotBlank() }?.let { sb.append(",\"recurrence\":[\"RRULE:${it.removePrefix("RRULE:")}\"]") }
        sb.append("}")
        return sb.toString()
    }

    private fun q(s: String) = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    private fun kotlinx.serialization.json.JsonPrimitive.contentSafe(): String? =
        runCatching { content }.getOrNull()?.ifBlank { null }

    companion object {
        private const val TAG = "GoogleCalendarClient"
    }
}
