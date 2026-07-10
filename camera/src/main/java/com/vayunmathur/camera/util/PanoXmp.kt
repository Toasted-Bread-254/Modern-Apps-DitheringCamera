package com.vayunmathur.camera.util

import java.io.ByteArrayOutputStream

/**
 * Builds and injects a standard GPano XMP packet so stitched panoramas/spheres
 * are recognised as 360/panoramic by compliant viewers (Google Photos, VR
 * viewers, etc.). Uses only the JPEG APP1 segment — no extra dependency.
 */
object PanoXmp {

    private const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/\u0000"

    /** Emit a standard XMP packet carrying the GPano fields for [info]. */
    fun buildGPanoXmp(info: PanoInfo): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append("<rdf:Description rdf:about=\"\" ")
        append("xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\" ")
        append("GPano:UsePanoramaViewer=\"True\" ")
        append("GPano:ProjectionType=\"${info.projectionType}\" ")
        append("GPano:FullPanoWidthPixels=\"${info.fullWidth}\" ")
        append("GPano:FullPanoHeightPixels=\"${info.fullHeight}\" ")
        append("GPano:CroppedAreaImageWidthPixels=\"${info.croppedWidth}\" ")
        append("GPano:CroppedAreaImageHeightPixels=\"${info.croppedHeight}\" ")
        append("GPano:CroppedAreaLeftPixels=\"${info.croppedLeft}\" ")
        append("GPano:CroppedAreaTopPixels=\"${info.croppedTop}\"/>")
        append("</rdf:RDF>")
        append("</x:xmpmeta>")
        append("<?xpacket end=\"w\"?>")
    }

    /**
     * Insert an APP1 (0xFFE1) XMP segment right after the SOI marker (0xFFD8).
     * Payload = namespace signature + XMP bytes; length is a 2-byte big-endian
     * value covering the length field + payload (must fit in 65533 bytes, which
     * these few GPano fields always do).
     */
    fun injectXmp(jpeg: ByteArray, xmp: String): ByteArray {
        // Not a JPEG (no SOI) — return unchanged rather than corrupting it.
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg

        val nsBytes = XMP_NAMESPACE.toByteArray(Charsets.UTF_8)
        val xmpBytes = xmp.toByteArray(Charsets.UTF_8)
        val payloadLen = nsBytes.size + xmpBytes.size
        val segmentLen = payloadLen + 2 // includes the 2-byte length field itself
        if (segmentLen > 0xFFFF) return jpeg

        val out = ByteArrayOutputStream(jpeg.size + segmentLen + 2)
        // SOI
        out.write(0xFF)
        out.write(0xD8)
        // APP1 marker + length + payload
        out.write(0xFF)
        out.write(0xE1)
        out.write((segmentLen shr 8) and 0xFF)
        out.write(segmentLen and 0xFF)
        out.write(nsBytes)
        out.write(xmpBytes)
        // Rest of the original JPEG after the SOI.
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }
}
