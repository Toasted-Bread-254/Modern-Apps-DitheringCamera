package com.vayunmathur.everysync.remote

import android.util.Log
import com.vayunmathur.everysync.auth.DavCredentials
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.SimpleResponse
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.encoding.Base64

/** A single WebDAV resource (vCard / iCalendar object) with its ETag. */
data class DavResource(
    val href: String,
    val etag: String?,
    val data: String? = null,
)

/** A discovered DAV collection (addressbook or calendar). */
data class DavCollection(
    val url: String,
    val displayName: String,
    val ctag: String? = null,
    val color: Int? = null,
)

/**
 * WebDAV client for CalDAV (RFC 4791) and CardDAV (RFC 6352) over the shared
 * ktor [NetworkClient], using the custom methods PROPFIND / REPORT / PUT / DELETE.
 * Change detection uses collection ctags and per-resource ETags; the caller
 * decides what to re-fetch.
 */
class DavClient(private val creds: DavCredentials) {

    private fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val basic = Base64.encode("${creds.username}:${creds.password}".toByteArray())
        return mapOf("Authorization" to "Basic $basic") + extra
    }

    private suspend fun request(url: String, method: String, headers: Map<String, String>, body: String?): SimpleResponse =
        NetworkClient.performRequest(url, method, headers, body)

    /** Discover addressbook or calendar collections under [baseUrl]. */
    suspend fun discoverCollections(baseUrl: String, isCalendar: Boolean): List<DavCollection> {
        val propBody = if (isCalendar) {
            """<?xml version="1.0"?>
               <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:ic="http://apple.com/ns/ical/">
                 <d:prop><d:resourcetype/><d:displayname/><cs:getctag/><ic:calendar-color/></d:prop>
               </d:propfind>"""
        } else {
            """<?xml version="1.0"?>
               <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                 <d:prop><d:resourcetype/><d:displayname/><cs:getctag/></d:prop>
               </d:propfind>"""
        }
        val collectionTag = if (isCalendar) "calendar" else "addressbook"
        val out = mutableListOf<DavCollection>()
        try {
            val resp = request(baseUrl, "PROPFIND", authHeaders(mapOf("Depth" to "1", "Content-Type" to "application/xml")), propBody)
            val responses = parseResponses(resp.body)
            for (r in responses) {
                val types = r.resourceTypes
                if (types.contains(collectionTag)) {
                    out += DavCollection(
                        url = resolve(baseUrl, r.href),
                        displayName = r.displayName ?: r.href.trimEnd('/').substringAfterLast('/'),
                        ctag = r.ctag,
                        color = r.color,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "discoverCollections failed", e)
        }
        // Fallback: treat the URL itself as the collection.
        if (out.isEmpty()) {
            out += DavCollection(url = baseUrl, displayName = baseUrl.trimEnd('/').substringAfterLast('/'))
        }
        return out
    }

    /** List resources (href + etag only) in a collection. */
    suspend fun listResources(collectionUrl: String): List<DavResource> {
        val body = """<?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>"""
        return try {
            val resp = request(collectionUrl, "PROPFIND", authHeaders(mapOf("Depth" to "1", "Content-Type" to "application/xml")), body)
            parseResponses(resp.body)
                .filter { !it.href.trimEnd('/').equals(URI(collectionUrl).path.trimEnd('/')) && it.etag != null }
                .map { DavResource(resolve(collectionUrl, it.href), it.etag) }
        } catch (e: Exception) {
            Log.e(TAG, "listResources failed", e); emptyList()
        }
    }

    /** Fetch full object bodies (address-data / calendar-data) for the given hrefs. */
    suspend fun multiget(collectionUrl: String, hrefs: List<String>, isCalendar: Boolean): List<DavResource> {
        if (hrefs.isEmpty()) return emptyList()
        val ns = if (isCalendar) "urn:ietf:params:xml:ns:caldav" else "urn:ietf:params:xml:ns:carddav"
        val reportName = if (isCalendar) "c:calendar-multiget" else "c:addressbook-multiget"
        val dataElem = if (isCalendar) "c:calendar-data" else "c:address-data"
        val hrefXml = hrefs.joinToString("") { "<d:href>${URI(it).path}</d:href>" }
        val body = """<?xml version="1.0"?>
            <$reportName xmlns:d="DAV:" xmlns:c="$ns">
              <d:prop><d:getetag/><$dataElem/></d:prop>
              $hrefXml
            </$reportName>"""
        return try {
            val resp = request(collectionUrl, "REPORT", authHeaders(mapOf("Depth" to "1", "Content-Type" to "application/xml")), body)
            parseResponses(resp.body).map { DavResource(resolve(collectionUrl, it.href), it.etag, it.data) }
        } catch (e: Exception) {
            Log.e(TAG, "multiget failed", e); emptyList()
        }
    }

    /** PUT an object, returning the new ETag if the server supplied one. */
    suspend fun put(url: String, contentType: String, body: String, ifMatch: String? = null): String? {
        val headers = authHeaders(buildMap {
            put("Content-Type", contentType)
            if (ifMatch != null) put("If-Match", ifMatch)
        })
        return try {
            val resp = request(url, "PUT", headers, body)
            resp.headers["ETag"]?.firstOrNull() ?: resp.headers["Etag"]?.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "put failed", e); null
        }
    }

    suspend fun delete(url: String, ifMatch: String? = null) {
        val headers = authHeaders(if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap())
        try {
            request(url, "DELETE", headers, null)
        } catch (e: Exception) {
            Log.e(TAG, "delete failed", e)
        }
    }

    // --- XML parsing ---

    private data class ParsedResponse(
        val href: String,
        val etag: String?,
        val data: String?,
        val displayName: String?,
        val ctag: String?,
        val color: Int?,
        val resourceTypes: Set<String>,
    )

    private fun parseResponses(xml: String): List<ParsedResponse> {
        if (xml.isBlank()) return emptyList()
        val out = mutableListOf<ParsedResponse>()
        try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
            val responses = doc.getElementsByTagNameNS("*", "response")
            for (i in 0 until responses.length) {
                val resp = responses.item(i) as? Element ?: continue
                val href = firstText(resp, "href") ?: continue
                out += ParsedResponse(
                    href = href,
                    etag = firstText(resp, "getetag")?.trim('"'),
                    data = firstText(resp, "address-data") ?: firstText(resp, "calendar-data"),
                    displayName = firstText(resp, "displayname"),
                    ctag = firstText(resp, "getctag"),
                    color = firstText(resp, "calendar-color")?.let { parseColor(it) },
                    resourceTypes = resourceTypes(resp),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseResponses failed", e)
        }
        return out
    }

    private fun firstText(scope: Element, localName: String): String? {
        val nodes = scope.getElementsByTagNameNS("*", localName)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim()?.ifBlank { null } else null
    }

    private fun resourceTypes(resp: Element): Set<String> {
        val types = mutableSetOf<String>()
        val rt = resp.getElementsByTagNameNS("*", "resourcetype")
        if (rt.length > 0) {
            val children = (rt.item(0) as Element).childNodes
            for (i in 0 until children.length) {
                (children.item(i) as? Element)?.localName?.let { types += it.lowercase() }
            }
        }
        return types
    }

    private fun parseColor(value: String): Int? = try {
        val hex = value.trim().removePrefix("#").take(6)
        (0xFF000000.toInt()) or hex.toInt(16)
    } catch (_: Exception) { null }

    private fun resolve(base: String, href: String): String = try {
        URI(base).resolve(href).toString()
    } catch (_: Exception) { href }

    companion object {
        private const val TAG = "DavClient"
    }
}
