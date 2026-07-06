package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideDocCodecTest {

    private fun frame(text: String) = OdfSlideElement.Frame(
        OdfFrame(x = 0f, y = 0f, width = 100f, height = 40f, paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text)))))
    )

    private fun deck(vararg frameTexts: String) = OdfDocument.Presentation(
        title = "p", slides = listOf(OdfSlide("Slide 1", elements = frameTexts.map { frame(it) }))
    )

    private fun frameTexts(doc: OdfDocument.Presentation) =
        doc.slides[0].elements.map { (it as OdfSlideElement.Frame).frame.paragraphs.joinToString("") { p -> p.spans.joinToString("") { s -> s.text } } }

    @Test
    fun roundtrip_preserves_frames_and_bold() {
        val doc = OdfDocument.Presentation(title = "p", slides = listOf(OdfSlide("Intro", elements = listOf(
            OdfSlideElement.Frame(OdfFrame(10f, 20f, 300f, 80f, listOf(OdfParagraph(listOf(OdfSpan("Title", bold = true)))))),
            frame("Body text")
        ))))
        val rebuilt = SlideDocCodec.fromCells(SlideDocCodec.toCells(doc), doc)
        assertEquals(frameTexts(doc), frameTexts(rebuilt))
        assertEquals("Intro", rebuilt.slides[0].name)
        val firstFrame = (rebuilt.slides[0].elements[0] as OdfSlideElement.Frame).frame
        assertEquals(10f, firstFrame.x)
        assertTrue(firstFrame.paragraphs[0].spans[0].bold)
    }

    @Test
    fun concurrent_edits_same_textbox_merge_char_level() {
        val base = deck("Hello")
        val a = DocumentCrdt("A"); val baseOps = a.update(SlideDocCodec.toCells(base))
        val b = DocumentCrdt("B"); b.apply(baseOps)
        val opsA = a.update(SlideDocCodec.toCells(deck("Hello World")))
        val opsB = b.update(SlideDocCodec.toCells(deck("Hi Hello")))
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val merged = SlideDocCodec.fromCells(a.render(), base)
        val text = frameTexts(merged)[0]
        assertTrue("has A's edit", text.contains("World"))
        assertTrue("has B's edit", text.contains("Hi"))
        assertEquals("still one text box (no duplication)", 1, merged.slides[0].elements.size)
    }

    @Test
    fun ineligible_when_slide_has_image_frame() {
        assertTrue(SlideDocCodec.isEligible(deck("a")))
        val withImage = OdfDocument.Presentation(title = "p", slides = listOf(OdfSlide("S", elements = listOf(
            OdfSlideElement.Frame(OdfFrame(0f, 0f, 10f, 10f, emptyList(), image = com.vayunmathur.library.ui.odf.OdfImage(path = "x", imageData = ByteArray(0))))
        ))))
        assertTrue(!SlideDocCodec.isEligible(withImage))
    }
}
