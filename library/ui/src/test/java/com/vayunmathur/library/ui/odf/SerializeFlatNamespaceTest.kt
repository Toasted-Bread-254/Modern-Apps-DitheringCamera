package com.vayunmathur.library.ui.odf

import org.junit.Assert.assertTrue
import org.junit.Test

class SerializeFlatNamespaceTest {
    @Test
    fun flat_declares_meta_namespace_before_use() {
        val doc = OdfDocument.TextDocument(
            title = "Hello",
            content = listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("hi")))))
        )
        val flat = OdfSerializer.serializeFlat(doc)
        assertTrue("flat ODF must be a single office:document", flat.contains("<office:document"))
        // Any meta:-prefixed element must be preceded by an xmlns:meta declaration on the root.
        if (flat.contains("<meta:")) {
            assertTrue(
                "xmlns:meta must be declared before the first <meta: element",
                flat.substringBefore("<meta:").contains("xmlns:meta=")
            )
        }
    }
}
