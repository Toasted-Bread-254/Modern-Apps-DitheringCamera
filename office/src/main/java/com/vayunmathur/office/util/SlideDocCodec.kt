package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Projects an [OdfDocument.Presentation] to/from a flat cell list so [DocumentCrdt] merges slide
 * text-box text at character granularity. Eligible only for presentations whose elements are all
 * text frames (no images/charts/shapes, no slide notes/background image); richer decks use the
 * lossless line-level codec.
 *
 * Layout: per slide, per frame, per paragraph → the paragraph's characters (each carrying its span
 * style), a `P` terminator (paragraph style), an `E` terminator per frame (geometry/fill), and a `D`
 * terminator per slide (name/background/transition).
 */
object SlideDocCodec {
    private const val SEP = '\u0002'
    private const val CSEP = '\u0003'
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class SpanStyle(val b: Boolean = false, val i: Boolean = false, val u: Boolean = false, val sz: Float? = null, val col: Long? = null)

    @Serializable
    data class FrameMeta(val x: Float, val y: Float, val w: Float, val h: Float, val fill: Long? = null, val stroke: Long? = null, val strokeW: Float? = null)

    @Serializable
    data class SlideMeta(val name: String, val bg: Long? = null, val transitionType: String? = null, val transitionSpeed: String? = null, val masterName: String? = null)

    fun isEligible(doc: OdfDocument): Boolean {
        if (doc !is OdfDocument.Presentation) return false
        if (doc.images.isNotEmpty()) return false
        for (slide in doc.slides) {
            if (slide.backgroundImagePath != null || slide.notes.isNotEmpty()) return false
            for (el in slide.elements) {
                val f = (el as? OdfSlideElement.Frame)?.frame ?: return false
                if (f.image != null || f.chart != null) return false
            }
        }
        return true
    }

    fun toCells(doc: OdfDocument.Presentation): List<String> {
        val cells = ArrayList<String>()
        for (slide in doc.slides) {
            for (el in slide.elements) {
                val f = (el as OdfSlideElement.Frame).frame
                for (para in f.paragraphs) {
                    for (span in para.spans) {
                        val key = json.encodeToString(spanStyle(span))
                        for (ch in span.text) cells.add("c$SEP$key$CSEP$ch")
                    }
                    cells.add("P$SEP${para.style.name}")
                }
                cells.add("E$SEP${json.encodeToString(frameMeta(f))}")
            }
            cells.add("D$SEP${json.encodeToString(slideMeta(slide))}")
        }
        return cells
    }

    fun fromCells(cells: List<String>, base: OdfDocument.Presentation): OdfDocument.Presentation {
        val slides = ArrayList<OdfSlide>()
        var elements = ArrayList<OdfSlideElement>()
        var paras = ArrayList<OdfParagraph>()
        var spans = ArrayList<OdfSpan>()
        var curKey: String? = null
        val text = StringBuilder()

        fun flushSpan() {
            if (curKey != null && text.isNotEmpty()) {
                val s = runCatching { json.decodeFromString<SpanStyle>(curKey!!) }.getOrNull() ?: SpanStyle()
                spans.add(OdfSpan(text = text.toString(), bold = s.b, italic = s.i, underline = s.u, fontSize = s.sz, color = s.col))
            }
            text.clear()
        }
        fun flushPara(styleName: String) {
            flushSpan()
            val style = runCatching { ParagraphStyle.valueOf(styleName) }.getOrNull() ?: ParagraphStyle.BODY
            paras.add(OdfParagraph(spans = if (spans.isEmpty()) listOf(OdfSpan("")) else spans, style = style))
            spans = ArrayList(); curKey = null
        }

        for (cell in cells) {
            val sep = cell.indexOf(SEP)
            if (sep < 0) continue
            val kind = cell.substring(0, sep)
            val value = cell.substring(sep + 1)
            when (kind) {
                "c" -> {
                    val cs = value.indexOf(CSEP)
                    if (cs < 0) continue
                    val key = value.substring(0, cs); val ch = value.substring(cs + 1)
                    if (key != curKey) { flushSpan(); curKey = key }
                    text.append(ch)
                }
                "P" -> flushPara(value)
                "E" -> {
                    val m = runCatching { json.decodeFromString<FrameMeta>(value) }.getOrNull() ?: FrameMeta(0f, 0f, 100f, 40f)
                    elements.add(OdfSlideElement.Frame(OdfFrame(
                        x = m.x, y = m.y, width = m.w, height = m.h, paragraphs = paras,
                        fillColor = m.fill, strokeColor = m.stroke, strokeWidth = m.strokeW,
                    )))
                    paras = ArrayList()
                }
                "D" -> {
                    val m = runCatching { json.decodeFromString<SlideMeta>(value) }.getOrNull() ?: SlideMeta("Slide")
                    slides.add(OdfSlide(
                        name = m.name, elements = elements, backgroundColor = m.bg,
                        transitionType = m.transitionType, transitionSpeed = m.transitionSpeed, masterName = m.masterName,
                    ))
                    elements = ArrayList()
                }
            }
        }
        if (slides.isEmpty()) slides.add(OdfSlide("Slide 1"))
        return base.copy(slides = slides)
    }

    private fun spanStyle(s: OdfSpan) = SpanStyle(b = s.bold, i = s.italic, u = s.underline, sz = s.fontSize, col = s.color)
    private fun frameMeta(f: OdfFrame) = FrameMeta(f.x, f.y, f.width, f.height, f.fillColor, f.strokeColor, f.strokeWidth)
    private fun slideMeta(s: OdfSlide) = SlideMeta(s.name, s.backgroundColor, s.transitionType, s.transitionSpeed, s.masterName)
}
