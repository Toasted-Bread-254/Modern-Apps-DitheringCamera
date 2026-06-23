package com.vayunmathur.office.odf

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object OdfWriter {

    fun save(context: Context, sourceUri: Uri, document: OdfDocument, targetUri: Uri) {
        val result = OdfSerializer.serializePackaged(document)
        val contentXml = result.contentXml
        val metaXml = OdfSerializer.serializeMeta(document.metadata)
        val docImages = imagesOf(document)
        val written = mutableSetOf<String>()
        // Media types for generated package parts (embedded chart objects, inline images).
        val extraManifest = LinkedHashMap<String, String>(result.manifest)

        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zipOut ->
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                ZipInputStream(input).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name == "content.xml" -> {
                                writeEntry(zipOut, "content.xml", contentXml.toByteArray(Charsets.UTF_8)); written.add(name)
                            }
                            name == "meta.xml" -> {
                                writeEntry(zipOut, "meta.xml", metaXml.toByteArray(Charsets.UTF_8)); written.add(name)
                            }
                            // Regenerated below; never copy the old manifest.
                            name == "META-INF/manifest.xml" -> {}
                            !entry.isDirectory -> {
                                zipOut.putNextEntry(ZipEntry(name)); zipIn.copyTo(zipOut); zipOut.closeEntry(); written.add(name)
                            }
                        }
                        entry = zipIn.nextEntry
                    }
                }
            }
            if ("content.xml" !in written) {
                writeEntry(zipOut, "content.xml", contentXml.toByteArray(Charsets.UTF_8)); written.add("content.xml")
            }
            if ("meta.xml" !in written) {
                writeEntry(zipOut, "meta.xml", metaXml.toByteArray(Charsets.UTF_8)); written.add("meta.xml")
            }
            // Document images (inserted via the editor) not already in the package. (A6)
            for ((path, bytes) in docImages) {
                if (path == "inline" || path.isBlank() || path in written || bytes.isEmpty()) continue
                writeEntry(zipOut, path, bytes); written.add(path)
                extraManifest[path] = mediaTypeFor(path, document)
            }
            // Inline images promoted to the package during serialization. (A6)
            for ((path, bytes) in result.images) {
                if (path in written || bytes.isEmpty()) continue
                writeEntry(zipOut, path, bytes); written.add(path)
            }
            // Embedded chart objects. (A8)
            for ((path, xml) in result.objects) {
                if (path in written) continue
                writeEntry(zipOut, path, xml.toByteArray(Charsets.UTF_8)); written.add(path)
            }
            // Regenerate META-INF/manifest.xml listing everything actually in the package.
            val manifestXml = buildManifest(document, written, extraManifest)
            writeEntry(zipOut, "META-INF/manifest.xml", manifestXml.toByteArray(Charsets.UTF_8))
        }

        context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
            buffer.writeTo(output)
        }
    }

    private fun writeEntry(zipOut: ZipOutputStream, name: String, bytes: ByteArray) {
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    /** Rebuilds META-INF/manifest.xml from the final package contents so new images/objects are declared. (A6/A8) */
    private fun buildManifest(document: OdfDocument, written: Set<String>, extra: Map<String, String>): String {
        val mime = documentMime(document)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3">""")
        sb.append("""<manifest:file-entry manifest:full-path="/" manifest:version="1.3" manifest:media-type="$mime"/>""")
        // Embedded-object directory entries (declared first so readers register the sub-documents).
        for ((path, type) in extra) {
            if (path.endsWith("/")) sb.append("""<manifest:file-entry manifest:full-path="$path" manifest:media-type="$type"/>""")
        }
        val seen = mutableSetOf("/")
        fun add(path: String, type: String) {
            if (path in seen) return
            seen.add(path)
            sb.append("""<manifest:file-entry manifest:full-path="$path" manifest:media-type="$type"/>""")
        }
        for ((path, type) in extra) if (!path.endsWith("/")) add(path, type)
        for (path in written) {
            if (path == "mimetype" || path == "META-INF/manifest.xml") continue
            add(path, mediaTypeFor(path, document))
        }
        sb.append("</manifest:manifest>")
        return sb.toString()
    }

    private fun documentMime(document: OdfDocument): String = when (document) {
        is OdfDocument.TextDocument -> "application/vnd.oasis.opendocument.text"
        is OdfDocument.Spreadsheet -> "application/vnd.oasis.opendocument.spreadsheet"
        is OdfDocument.Presentation -> "application/vnd.oasis.opendocument.presentation"
        is OdfDocument.Drawing -> "application/vnd.oasis.opendocument.graphics"
    }

    private fun mediaTypeFor(path: String, document: OdfDocument): String = when {
        path == "content.xml" || path == "styles.xml" || path == "meta.xml" || path == "settings.xml" -> "text/xml"
        path.endsWith("/content.xml") || path.endsWith("/styles.xml") -> "text/xml"
        path.endsWith(".xml") -> "text/xml"
        path.endsWith(".rdf") -> "application/rdf+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".bmp") -> "image/bmp"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".webp") -> "image/webp"
        else -> "application/octet-stream"
    }

    private fun imagesOf(document: OdfDocument): Map<String, ByteArray> = when (document) {
        is OdfDocument.TextDocument -> document.images
        is OdfDocument.Spreadsheet -> document.images
        is OdfDocument.Presentation -> document.images
        is OdfDocument.Drawing -> document.images
    }

    fun saveAs(context: Context, sourceUri: Uri, document: OdfDocument, targetUri: Uri) {
        save(context, sourceUri, document, targetUri)
    }
}
