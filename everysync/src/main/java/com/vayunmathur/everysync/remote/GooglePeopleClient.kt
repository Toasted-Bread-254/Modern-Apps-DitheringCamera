package com.vayunmathur.everysync.remote

import android.util.Log
import com.vayunmathur.everysync.model.RemoteContact
import com.vayunmathur.everysync.model.TypedValue
import com.vayunmathur.library.network.NetworkClient
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GoogleContactsResult(val contacts: List<RemoteContact>, val nextSyncToken: String?)

/**
 * Google People API client. Incremental sync uses `syncToken`: pass the token
 * from the previous pull to receive only changes (including deletions).
 */
class GooglePeopleClient(private val accessToken: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val fields = "names,emailAddresses,phoneNumbers,addresses,organizations,biographies,birthdays,metadata"

    private fun headers() = mapOf("Authorization" to "Bearer $accessToken")

    /** Pull connections. [syncToken] null = full sync (also requests a token). */
    suspend fun listConnections(syncToken: String?): GoogleContactsResult {
        val contacts = mutableListOf<RemoteContact>()
        var pageToken: String? = null
        var nextSyncToken: String? = null
        try {
            do {
                val url = buildString {
                    append("https://people.googleapis.com/v1/people/me/connections")
                    append("?personFields=$fields&pageSize=200&requestSyncToken=true")
                    if (syncToken != null) append("&syncToken=$syncToken")
                    if (pageToken != null) append("&pageToken=$pageToken")
                }
                val resp = NetworkClient.performRequest(url, "GET", headers())
                val root = json.parseToJsonElement(resp.body) as? JsonObject ?: break
                (root["connections"] as? JsonArray)?.forEach { person ->
                    parsePerson(person.jsonObject)?.let { contacts += it }
                }
                pageToken = root["nextPageToken"]?.jsonPrimitive?.contentSafe()
                nextSyncToken = root["nextSyncToken"]?.jsonPrimitive?.contentSafe() ?: nextSyncToken
            } while (pageToken != null)
        } catch (e: Exception) {
            Log.e(TAG, "listConnections failed", e)
        }
        return GoogleContactsResult(contacts, nextSyncToken)
    }

    /** Create a contact from a local edit. Returns the new resourceName (uid). */
    suspend fun createContact(c: RemoteContact): String? {
        return try {
            val resp = NetworkClient.performRequest(
                "https://people.googleapis.com/v1/people:createContact",
                "POST", headers() + mapOf("Content-Type" to "application/json"), personBody(c),
            )
            (json.parseToJsonElement(resp.body) as? JsonObject)?.get("resourceName")?.jsonPrimitive?.contentSafe()
        } catch (e: Exception) {
            Log.e(TAG, "createContact failed", e); null
        }
    }

    suspend fun deleteContact(resourceName: String) {
        try {
            NetworkClient.performRequest(
                "https://people.googleapis.com/v1/$resourceName:deleteContact", "DELETE", headers(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "deleteContact failed", e)
        }
    }

    private fun parsePerson(p: JsonObject): RemoteContact? {
        val resourceName = p["resourceName"]?.jsonPrimitive?.contentSafe() ?: return null
        val etag = p["etag"]?.jsonPrimitive?.contentSafe()
        val deleted = (p["metadata"] as? JsonObject)?.get("deleted")?.jsonPrimitive?.contentSafe() == "true"
        if (deleted) return RemoteContact(uid = resourceName, etag = etag, deleted = true)

        val name = (p["names"] as? JsonArray)?.firstOrNull()?.jsonObject
        val org = (p["organizations"] as? JsonArray)?.firstOrNull()?.jsonObject
        val note = (p["biographies"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("value")?.jsonPrimitive?.contentSafe() ?: ""
        val bday = (p["birthdays"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("date")?.jsonObject?.let { d ->
            val y = d["year"]?.jsonPrimitive?.contentSafe() ?: "1604"
            val m = d["month"]?.jsonPrimitive?.contentSafe()?.padStart(2, '0') ?: return@let null
            val day = d["day"]?.jsonPrimitive?.contentSafe()?.padStart(2, '0') ?: return@let null
            "$y-$m-$day"
        }
        return RemoteContact(
            uid = resourceName,
            etag = etag,
            displayName = name?.get("displayName")?.jsonPrimitive?.contentSafe() ?: "",
            firstName = name?.get("givenName")?.jsonPrimitive?.contentSafe() ?: "",
            middleName = name?.get("middleName")?.jsonPrimitive?.contentSafe() ?: "",
            lastName = name?.get("familyName")?.jsonPrimitive?.contentSafe() ?: "",
            prefix = name?.get("honorificPrefix")?.jsonPrimitive?.contentSafe() ?: "",
            suffix = name?.get("honorificSuffix")?.jsonPrimitive?.contentSafe() ?: "",
            organization = org?.get("name")?.jsonPrimitive?.contentSafe() ?: "",
            note = note,
            birthday = bday,
            phones = (p["phoneNumbers"] as? JsonArray)?.mapNotNull {
                it.jsonObject["value"]?.jsonPrimitive?.contentSafe()?.let { v -> TypedValue(v, Phone.TYPE_MOBILE) }
            } ?: emptyList(),
            emails = (p["emailAddresses"] as? JsonArray)?.mapNotNull {
                it.jsonObject["value"]?.jsonPrimitive?.contentSafe()?.let { v -> TypedValue(v, Email.TYPE_HOME) }
            } ?: emptyList(),
            addresses = (p["addresses"] as? JsonArray)?.mapNotNull {
                it.jsonObject["formattedValue"]?.jsonPrimitive?.contentSafe()?.let { v -> TypedValue(v, 1) }
            } ?: emptyList(),
        )
    }

    private fun personBody(c: RemoteContact): String {
        val sb = StringBuilder("{")
        sb.append("\"names\":[{\"givenName\":${q(c.firstName)},\"familyName\":${q(c.lastName)}}]")
        if (c.emails.isNotEmpty()) sb.append(",\"emailAddresses\":[${c.emails.joinToString(",") { "{\"value\":${q(it.value)}}" }}]")
        if (c.phones.isNotEmpty()) sb.append(",\"phoneNumbers\":[${c.phones.joinToString(",") { "{\"value\":${q(it.value)}}" }}]")
        if (c.organization.isNotBlank()) sb.append(",\"organizations\":[{\"name\":${q(c.organization)}}]")
        sb.append("}")
        return sb.toString()
    }

    private fun q(s: String) = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun kotlinx.serialization.json.JsonPrimitive.contentSafe(): String? =
        runCatching { content }.getOrNull()?.ifBlank { null }

    companion object {
        private const val TAG = "GooglePeopleClient"
    }
}
