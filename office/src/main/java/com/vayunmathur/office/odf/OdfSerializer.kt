package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import java.util.Locale

object OdfSerializer {

    /** Extra package parts generated during serialization (inline images promoted to the package,
     *  embedded chart objects, and the manifest media types for those objects). (A6/A8) */
    data class SerResult(
        val contentXml: String,
        val images: Map<String, ByteArray>,
        val objects: Map<String, String>,
        val manifest: Map<String, String>
    )

    private class SerCtx(val flat: Boolean) {
        val images = LinkedHashMap<String, ByteArray>()
        val objects = LinkedHashMap<String, String>()
        val manifest = LinkedHashMap<String, String>()
        private var imgN = 0
        private var objN = 0
        fun nextImagePath(ext: String): String { imgN++; return "Pictures/inline$imgN.$ext" }
        fun nextObjectDir(): String { objN++; return "Object Chart $objN" }
    }

    /** Content-only serialization (used for in-package content.xml via [serializePackaged] and flat export). */
    fun serialize(document: OdfDocument): String = serializeInner(document, SerCtx(flat = true))

    /** Full package serialization: content.xml plus generated inline images and embedded chart objects. */
    fun serializePackaged(document: OdfDocument): SerResult {
        val ctx = SerCtx(flat = false)
        val xml = serializeInner(document, ctx)
        return SerResult(xml, ctx.images, ctx.objects, ctx.manifest)
    }

    private fun serializeInner(document: OdfDocument, ctx: SerCtx): String {
        return when (document) {
            is OdfDocument.TextDocument -> serializeTextDocument(document, ctx)
            is OdfDocument.Spreadsheet -> serializeSpreadsheet(document, ctx)
            is OdfDocument.Presentation -> serializePresentation(document, ctx)
            is OdfDocument.Drawing -> serializePresentation(
                OdfDocument.Presentation(document.title, document.pages, document.metadata, document.images), ctx
            )
        }
    }

    /** Serializes to a flat ODF (.fodt/.fods/.fodp) single-XML document (K75). */
    fun serializeFlat(document: OdfDocument): String {
        val content = serialize(document)
        val mimetype = when (document) {
            is OdfDocument.TextDocument -> "application/vnd.oasis.opendocument.text"
            is OdfDocument.Spreadsheet -> "application/vnd.oasis.opendocument.spreadsheet"
            is OdfDocument.Presentation -> "application/vnd.oasis.opendocument.presentation"
            is OdfDocument.Drawing -> "application/vnd.oasis.opendocument.graphics"
        }
        val metaFull = serializeMeta(document.metadata)
        val metaInner = if (metaFull.contains("<office:meta>"))
            "<office:meta>" + metaFull.substringAfter("<office:meta>").substringBefore("</office:meta>") + "</office:meta>"
        else ""
        var s = content.replace("<office:document-content", "<office:document")
        s = s.replace("""office:version="1.3">""", """office:mimetype="$mimetype" office:version="1.3">$metaInner""")
        s = s.replace("</office:document-content>", "</office:document>")
        return s
    }

    /** Generates meta.xml so edited document metadata persists on save (G47). */
    fun serializeMeta(meta: OdfMetadata): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" office:version="1.3"><office:meta>""")
        meta.title?.let { sb.append("<dc:title>${esc(it)}</dc:title>") }
        meta.creator?.let { sb.append("<meta:initial-creator>${esc(it)}</meta:initial-creator>") }
        meta.author?.let { sb.append("<dc:creator>${esc(it)}</dc:creator>") }
        meta.subject?.let { sb.append("<dc:subject>${esc(it)}</dc:subject>") }
        meta.description?.let { sb.append("<dc:description>${esc(it)}</dc:description>") }
        for (kw in meta.keywords) sb.append("<meta:keyword>${esc(kw)}</meta:keyword>")
        meta.creationDate?.let { sb.append("<meta:creation-date>${esc(it)}</meta:creation-date>") }
        meta.modifiedDate?.let { sb.append("<dc:date>${esc(it)}</dc:date>") }
        sb.append("</office:meta></office:document-meta>")
        return sb.toString()
    }

    private fun serializeTextDocument(doc: OdfDocument.TextDocument, ctx: SerCtx): String {
        val styles = mutableMapOf<String, SpanStyleDef>()
        val paraStyles = mutableMapOf<String, ParaStyleDef>()
        val graphicStyles = LinkedHashMap<String, GraphicStyleDef>()
        val body = StringBuilder()

        // Bookmarks keyed by the content-block index they precede. (B3)
        val bookmarksByIndex = HashMap<Int, MutableList<String>>()
        for (bk in doc.bookmarks) bookmarksByIndex.getOrPut(bk.contentIndex) { mutableListOf() }.add(bk.name)

        var listOpen = false
        doc.content.forEachIndexed { index, block ->
            val leadingBookmarks = bookmarksByIndex[index] ?: emptyList()
            when (block) {
                is OdfContentBlock.Paragraph -> {
                    val para = block.paragraph
                    if (para.style == ParagraphStyle.LIST_ITEM) {
                        if (!listOpen) { body.append("<text:list>"); listOpen = true }
                        body.append("<text:list-item>")
                        serializeParagraph(body, para, styles, paraStyles, "text:p", leadingBookmarks)
                        body.append("</text:list-item>")
                    } else {
                        if (listOpen) { body.append("</text:list>"); listOpen = false }
                        val tag = when (para.style) {
                            ParagraphStyle.HEADING1, ParagraphStyle.HEADING2,
                            ParagraphStyle.HEADING3, ParagraphStyle.HEADING4 -> "text:h"
                            else -> "text:p"
                        }
                        serializeParagraph(body, para, styles, paraStyles, tag, leadingBookmarks)
                    }
                }
                is OdfContentBlock.Table -> {
                    if (listOpen) { body.append("</text:list>"); listOpen = false }
                    serializeTable(body, block.table, styles, paraStyles)
                }
                is OdfContentBlock.Image -> {
                    if (listOpen) { body.append("</text:list>"); listOpen = false }
                    serializeImageRef(body, block.image, graphicStyles, ctx)
                }
                is OdfContentBlock.Chart -> {
                    if (listOpen) { body.append("</text:list>"); listOpen = false }
                    serializeChartFrame(body, block.chart, 0f, 0f, 480f, 320f, "paragraph", styles, paraStyles, ctx)
                }
                is OdfContentBlock.Formula -> {
                    if (listOpen) { body.append("</text:list>"); listOpen = false }
                    // Formulas are embedded math objects; preserved via original package, not re-serialized inline.
                }
                is OdfContentBlock.PageBreak -> {
                    if (listOpen) { body.append("</text:list>"); listOpen = false }
                    // Emit an empty paragraph carrying a page-break-before style. (B4)
                    val name = getOrCreateParaStyle(OdfParagraph(emptyList(), breakBeforePage = true), paraStyles)
                    body.append("""<text:p text:style-name="$name"/>""")
                }
            }
        }
        if (listOpen) body.append("</text:list>")

        return buildDocument("office:text", styles, paraStyles, LinkedHashMap(), LinkedHashMap(), graphicStyles, body.toString())
    }

    private fun serializeSpreadsheet(doc: OdfDocument.Spreadsheet, ctx: SerCtx): String {
        val cellStyles = LinkedHashMap<String, CellStyleDef>()
        val colStyles = LinkedHashMap<String, Float>()
        val spanStyles = mutableMapOf<String, SpanStyleDef>()
        val paraStyles = mutableMapOf<String, ParaStyleDef>()
        val graphicStyles = LinkedHashMap<String, GraphicStyleDef>()
        val body = StringBuilder()
        for (sheet in doc.sheets) {
            body.append("""<table:table table:name="${esc(sheet.name)}">""")
            val maxCols = sheet.rows.maxOfOrNull { it.cells.size } ?: 0
            for (c in 0 until maxCols) {
                val w = sheet.columnWidths.getOrNull(c)
                if (w != null && w > 0f) {
                    val name = getOrCreateColStyle(w, colStyles)
                    body.append("""<table:table-column table:style-name="$name"/>""")
                } else {
                    body.append("<table:table-column/>")
                }
            }
            for (row in sheet.rows) {
                body.append("<table:table-row>")
                for (cell in row.cells) {
                    if (cell.isCovered) {
                        body.append("<table:covered-table-cell/>")
                    } else {
                        body.append("<table:table-cell")
                        val styleName = getOrCreateCellStyle(cell, cellStyles)
                        if (styleName != null) body.append(""" table:style-name="$styleName"""")
                        if (cell.spannedColumns > 1) body.append(""" table:number-columns-spanned="${cell.spannedColumns}"""")
                        if (cell.rowSpan > 1) body.append(""" table:number-rows-spanned="${cell.rowSpan}"""")
                        if (cell.formula != null) body.append(""" table:formula="${esc(cell.formula)}"""")
                        val numeric = cell.numberValue ?: cell.text.toDoubleOrNull()
                        if (numeric != null && cell.valueType != "string") {
                            body.append(""" office:value-type="float" office:value="$numeric"""")
                        } else if (cell.text.isNotEmpty()) {
                            body.append(""" office:value-type="string"""")
                        }
                        body.append(">")
                        if (cell.text.isNotEmpty()) body.append("<text:p>${esc(cell.text)}</text:p>")
                        body.append("</table:table-cell>")
                    }
                }
                body.append("</table:table-row>")
            }
            // Floating objects anchored to the sheet (Phase 4).
            for (element in sheet.floating) {
                when (element) {
                    is OdfSlideElement.Frame -> serializeFrame(body, element.frame, spanStyles, paraStyles, graphicStyles, ctx)
                    is OdfSlideElement.Shape -> serializeShape(body, element.shape, spanStyles, paraStyles, graphicStyles)
                }
            }
            body.append("</table:table>")
        }
        return buildDocument("office:spreadsheet", spanStyles, paraStyles, cellStyles, colStyles, graphicStyles, body.toString())
    }

    private fun serializePresentation(doc: OdfDocument.Presentation, ctx: SerCtx): String {
        val styles = mutableMapOf<String, SpanStyleDef>()
        val paraStyles = mutableMapOf<String, ParaStyleDef>()
        val graphicStyles = LinkedHashMap<String, GraphicStyleDef>()
        val drawPageStyles = LinkedHashMap<String, Long>()
        val body = StringBuilder()
        for (slide in doc.slides) {
            body.append("<draw:page")
            // Slide background fill via a drawing-page style. (B2)
            slide.backgroundColor?.let { body.append(""" draw:style-name="${getOrCreateDrawPageStyle(it, drawPageStyles)}"""") }
            body.append(""" draw:name="${esc(slide.name)}">""")
            for (element in slide.elements) {
                when (element) {
                    is OdfSlideElement.Frame -> serializeFrame(body, element.frame, styles, paraStyles, graphicStyles, ctx)
                    is OdfSlideElement.Shape -> serializeShape(body, element.shape, styles, paraStyles, graphicStyles)
                }
            }
            // Speaker notes. (B2)
            if (slide.notes.isNotEmpty()) {
                body.append("<presentation:notes><draw:frame svg:x=\"1.5cm\" svg:y=\"12cm\" svg:width=\"18cm\" svg:height=\"10cm\"><draw:text-box>")
                for (note in slide.notes) serializeParagraph(body, note, styles, paraStyles, "text:p")
                body.append("</draw:text-box></draw:frame></presentation:notes>")
            }
            body.append("</draw:page>")
        }
        return buildDocument("office:presentation", styles, paraStyles, LinkedHashMap(), LinkedHashMap(), graphicStyles, body.toString(), drawPageStyles)
    }

    private fun serializeParagraph(
        sb: StringBuilder, para: OdfParagraph,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>,
        tag: String,
        leadingBookmarks: List<String> = emptyList()
    ) {
        sb.append("<$tag")
        val pStyleName = getOrCreateParaStyle(para, paraStyles)
        if (pStyleName != null) sb.append(""" text:style-name="$pStyleName"""")
        if (tag == "text:h") {
            val level = when (para.style) {
                ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2
                ParagraphStyle.HEADING3 -> 3; else -> 4
            }
            sb.append(""" text:outline-level="$level"""")
        }
        sb.append(">")
        for (name in leadingBookmarks) sb.append("""<text:bookmark text:name="${esc(name)}"/>""")
        for (span in para.spans) {
            if (span.annotation != null) {
                // Serialize comments as office:annotation so they round-trip. (B3)
                sb.append("<office:annotation>")
                span.annotation.author?.let { sb.append("<dc:creator>${esc(it)}</dc:creator>") }
                span.annotation.date?.let { sb.append("<dc:date>${esc(it)}</dc:date>") }
                for (p in span.annotation.paragraphs) serializeParagraph(sb, p, styles, paraStyles, "text:p")
                sb.append("</office:annotation>")
                continue
            }
            val needsStyle = span.bold || span.italic || span.underline || span.strikethrough ||
                span.color != null || span.fontSize != null || span.superscript || span.subscript || span.fontFamily != null
            if (span.href != null) {
                sb.append("""<text:a xlink:href="${esc(span.href)}" xlink:type="simple">""")
                if (needsStyle) {
                    val styleName = getOrCreateSpanStyle(span, styles)
                    sb.append("""<text:span text:style-name="$styleName">${esc(span.text)}</text:span>""")
                } else {
                    sb.append(esc(span.text))
                }
                sb.append("</text:a>")
            } else if (needsStyle) {
                val styleName = getOrCreateSpanStyle(span, styles)
                sb.append("""<text:span text:style-name="$styleName">${esc(span.text)}</text:span>""")
            } else {
                sb.append(esc(span.text))
            }
        }
        sb.append("</$tag>")
    }

    private fun serializeTable(
        sb: StringBuilder, table: OdfTable,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>
    ) {
        sb.append("""<table:table table:name="${esc(table.name)}">""")
        for (col in table.columns) sb.append("<table:table-column/>")
        for (row in table.rows) {
            sb.append("<table:table-row>")
            for (cell in row.cells) {
                if (cell.isCovered) {
                    sb.append("<table:covered-table-cell/>")
                } else {
                    sb.append("<table:table-cell")
                    if (cell.colSpan > 1) sb.append(""" table:number-columns-spanned="${cell.colSpan}"""")
                    if (cell.rowSpan > 1) sb.append(""" table:number-rows-spanned="${cell.rowSpan}"""")
                    sb.append(">")
                    for (para in cell.paragraphs) serializeParagraph(sb, para, styles, paraStyles, "text:p")
                    sb.append("</table:table-cell>")
                }
            }
            sb.append("</table:table-row>")
        }
        sb.append("</table:table>")
    }

    private fun serializeImageRef(sb: StringBuilder, image: OdfImage, graphicStyles: MutableMap<String, GraphicStyleDef>, ctx: SerCtx) {
        val href = resolveImageHref(image, ctx) ?: run {
            if (!ctx.flat) return
            // Flat export with no path: emit base64 binary-data.
            sb.append("""<draw:frame""")
            if (image.width > 0) sb.append(""" svg:width="${cm(image.width)}"""")
            if (image.height > 0) sb.append(""" svg:height="${cm(image.height)}"""")
            appendRotation(sb, image)
            sb.append("""><draw:image><office:binary-data>${android.util.Base64.encodeToString(image.imageData, android.util.Base64.NO_WRAP)}</office:binary-data></draw:image></draw:frame>""")
            return
        }
        sb.append("""<draw:frame""")
        getOrCreateGraphicStyle(null, null, null, clipString(image), graphicStyles)?.let { sb.append(""" draw:style-name="$it"""") }
        if (image.width > 0) sb.append(""" svg:width="${cm(image.width)}"""")
        if (image.height > 0) sb.append(""" svg:height="${cm(image.height)}"""")
        appendRotation(sb, image)
        sb.append("""><draw:image xlink:href="${esc(href)}" xlink:type="simple" xlink:actuate="onLoad"/>""")
        sb.append("</draw:frame>")
    }

    /** Resolves the package href for an image, promoting inline images to the package. (A6) */
    private fun resolveImageHref(image: OdfImage, ctx: SerCtx): String? {
        if (image.path != "inline" && image.path.isNotBlank()) return image.path
        if (image.imageData.isEmpty()) return null
        if (ctx.flat) return null
        val path = ctx.nextImagePath(sniffExt(image.imageData))
        ctx.images[path] = image.imageData
        ctx.manifest[path] = mediaTypeForImage(path)
        return path
    }

    /** ODF fo:clip="rect(top right bottom left)" with absolute cm lengths relative to the natural image size. (A7) */
    private fun clipString(image: OdfImage): String? {
        if (image.cropLeftPct <= 0f && image.cropTopPct <= 0f && image.cropRightPct <= 0f && image.cropBottomPct <= 0f) return null
        val wPx = if (image.naturalWidthPx > 0f) image.naturalWidthPx else image.width
        val hPx = if (image.naturalHeightPx > 0f) image.naturalHeightPx else image.height
        if (wPx <= 0f || hPx <= 0f) return null
        val top = cm(image.cropTopPct * hPx)
        val right = cm(image.cropRightPct * wPx)
        val bottom = cm(image.cropBottomPct * hPx)
        val left = cm(image.cropLeftPct * wPx)
        return "rect($top $right $bottom $left)"
    }

    private fun appendRotation(sb: StringBuilder, image: OdfImage) {
        if (image.rotationDegrees != 0f) {
            // ODF rotation is counter-clockwise radians; our model stores clockwise degrees. (B1)
            val rad = (-image.rotationDegrees.toDouble() * Math.PI / 180.0)
            sb.append(""" draw:transform="rotate(${String.format(Locale.US, "%.5f", rad)})"""")
        }
    }

    private fun serializeChartFrame(
        sb: StringBuilder, chart: OdfChart, x: Float, y: Float, w: Float, h: Float, anchor: String,
        styles: MutableMap<String, SpanStyleDef>, paraStyles: MutableMap<String, ParaStyleDef>, ctx: SerCtx
    ) {
        if (ctx.flat) {
            // Flat export: best-effort text summary.
            sb.append("<draw:frame")
            if (anchor.isNotEmpty()) sb.append(""" text:anchor-type="$anchor"""")
            sb.append(""" svg:width="${cm(w)}" svg:height="${cm(h)}"><draw:text-box>""")
            serializeParagraph(sb, OdfParagraph(listOf(OdfSpan(text = chartSummary(chart)))), styles, paraStyles, "text:p")
            sb.append("</draw:text-box></draw:frame>")
            return
        }
        val dir = ctx.nextObjectDir()
        ctx.objects["$dir/content.xml"] = generateChartXml(chart, w, h)
        ctx.manifest["$dir/"] = "application/vnd.oasis.opendocument.chart"
        ctx.manifest["$dir/content.xml"] = "text/xml"
        sb.append("<draw:frame")
        if (anchor.isNotEmpty()) sb.append(""" text:anchor-type="$anchor"""")
        if (x != 0f || y != 0f) sb.append(""" svg:x="${cm(x)}" svg:y="${cm(y)}"""")
        sb.append(""" svg:width="${cm(w)}" svg:height="${cm(h)}">""")
        sb.append("""<draw:object xlink:href="./$dir" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/>""")
        sb.append("</draw:frame>")
    }

    private fun chartSummary(chart: OdfChart): String {
        val series = chart.series.joinToString(", ") { it.name }
        return "[Chart: ${chart.type.name.lowercase()}] ${chart.title ?: ""} ${if (series.isNotEmpty()) "($series)" else ""}".trim()
    }

    private fun serializeFrame(
        sb: StringBuilder, frame: OdfFrame,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>,
        graphicStyles: MutableMap<String, GraphicStyleDef>,
        ctx: SerCtx
    ) {
        // Charts become embedded objects in a frame of their own. (A8)
        if (frame.chart != null) {
            serializeChartFrame(sb, frame.chart, frame.x, frame.y, frame.width, frame.height, "", styles, paraStyles, ctx)
            return
        }
        sb.append("<draw:frame")
        val clip = frame.image?.let { clipString(it) }
        getOrCreateGraphicStyle(frame.fillColor, frame.strokeColor, frame.strokeWidth, clip, graphicStyles)?.let { sb.append(""" draw:style-name="$it"""") }
        sb.append(""" svg:x="${cm(frame.x)}" svg:y="${cm(frame.y)}"""")
        sb.append(""" svg:width="${cm(frame.width)}" svg:height="${cm(frame.height)}"""")
        frame.image?.let { appendRotation(sb, it) }
        sb.append(">")
        if (frame.image != null) {
            val href = resolveImageHref(frame.image, ctx)
            if (href != null) {
                sb.append("""<draw:image xlink:href="${esc(href)}" xlink:type="simple"/>""")
            } else if (ctx.flat && frame.image.imageData.isNotEmpty()) {
                sb.append("""<draw:image><office:binary-data>${android.util.Base64.encodeToString(frame.image.imageData, android.util.Base64.NO_WRAP)}</office:binary-data></draw:image>""")
            }
        }
        if (frame.paragraphs.isNotEmpty()) {
            sb.append("<draw:text-box>")
            for (para in frame.paragraphs) serializeParagraph(sb, para, styles, paraStyles, "text:p")
            sb.append("</draw:text-box>")
        }
        sb.append("</draw:frame>")
    }

    private fun serializeShape(
        sb: StringBuilder, shape: OdfShape,
        styles: MutableMap<String, SpanStyleDef>,
        paraStyles: MutableMap<String, ParaStyleDef>,
        graphicStyles: MutableMap<String, GraphicStyleDef>
    ) {
        val tag = when (shape) {
            is OdfShape.Rect -> "draw:rect"
            is OdfShape.Ellipse -> "draw:ellipse"
            is OdfShape.Line -> "draw:line"
            is OdfShape.CustomShape -> "draw:custom-shape"
        }
        sb.append("<$tag")
        getOrCreateGraphicStyle(shape.fillColor, shape.strokeColor, shape.strokeWidth, null, graphicStyles)?.let { sb.append(""" draw:style-name="$it"""") }
        if (shape is OdfShape.Line) {
            // Lines use endpoint coordinates, not a bounding box. (A5)
            val x2 = if (shape.x2 != 0f || shape.y2 != 0f) shape.x2 else shape.x + shape.width
            val y2 = if (shape.x2 != 0f || shape.y2 != 0f) shape.y2 else shape.y + shape.height
            sb.append(""" svg:x1="${cm(shape.x)}" svg:y1="${cm(shape.y)}"""")
            sb.append(""" svg:x2="${cm(x2)}" svg:y2="${cm(y2)}"""")
        } else {
            sb.append(""" svg:x="${cm(shape.x)}" svg:y="${cm(shape.y)}"""")
            sb.append(""" svg:width="${cm(shape.width)}" svg:height="${cm(shape.height)}"""")
        }
        sb.append(">")
        for (para in shape.text) serializeParagraph(sb, para, styles, paraStyles, "text:p")
        sb.append("</$tag>")
    }

    // --- Embedded chart object generation (A8) ---

    private fun generateChartXml(chart: OdfChart, wPx: Float, hPx: Float): String {
        val chartClass = when (chart.type) {
            ChartType.LINE -> "chart:line"
            ChartType.PIE -> "chart:circle"
            ChartType.DONUT -> "chart:ring"
            ChartType.AREA -> "chart:area"
            ChartType.SCATTER -> "chart:scatter"
            else -> "chart:bar"
        }
        val rowCount = chart.categories.size
        val lastRow = rowCount + 1 // header is row 1
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<office:document-content""")
        sb.append(""" xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"""")
        sb.append(""" xmlns:chart="urn:oasis:names:tc:opendocument:xmlns:chart:1.0"""")
        sb.append(""" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"""")
        sb.append(""" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"""")
        sb.append(""" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"""")
        sb.append(""" xmlns:xlink="http://www.w3.org/1999/xlink"""")
        sb.append(""" office:version="1.3">""")
        sb.append("<office:body><office:chart>")
        sb.append("""<chart:chart chart:class="$chartClass" svg:width="${cm(wPx)}" svg:height="${cm(hPx)}">""")
        chart.title?.let { sb.append("""<chart:title><text:p>${esc(it)}</text:p></chart:title>""") }
        sb.append("<chart:plot-area>")
        sb.append("""<chart:axis chart:dimension="x" chart:name="primary-x"/>""")
        sb.append("""<chart:axis chart:dimension="y" chart:name="primary-y"/>""")
        chart.series.forEachIndexed { i, s ->
            val col = columnLetter(i + 1) // series start at column B
            sb.append("""<chart:series chart:values-cell-range-address="local-table.${'$'}$col${'$'}2:.${'$'}$col${'$'}$lastRow" chart:label-cell-address="local-table.${'$'}$col${'$'}1" chart:class="$chartClass">""")
            if (i == 0) sb.append("""<chart:categories table:cell-range-address="local-table.${'$'}A${'$'}2:.${'$'}A${'$'}$lastRow"/>""")
            sb.append("</chart:series>")
        }
        sb.append("</chart:plot-area>")
        // local-table mirroring categories + series values.
        sb.append("""<table:table table:name="local-table">""")
        sb.append("<table:table-header-rows><table:table-row><table:table-cell/>")
        for (s in chart.series) sb.append("""<table:table-cell office:value-type="string"><text:p>${esc(s.name)}</text:p></table:table-cell>""")
        sb.append("</table:table-row></table:table-header-rows>")
        sb.append("<table:table-rows>")
        for (r in 0 until rowCount) {
            sb.append("<table:table-row>")
            sb.append("""<table:table-cell office:value-type="string"><text:p>${esc(chart.categories[r])}</text:p></table:table-cell>""")
            for (s in chart.series) {
                val v = s.values.getOrNull(r) ?: 0f
                sb.append("""<table:table-cell office:value-type="float" office:value="$v"><text:p>$v</text:p></table:table-cell>""")
            }
            sb.append("</table:table-row>")
        }
        sb.append("</table:table-rows></table:table>")
        sb.append("</chart:chart></office:chart></office:body></office:document-content>")
        return sb.toString()
    }

    private fun columnLetter(index: Int): String {
        val sb = StringBuilder(); var n = index
        do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    // --- Style management ---

    private data class SpanStyleDef(
        val bold: Boolean = false, val italic: Boolean = false,
        val underline: Boolean = false, val strikethrough: Boolean = false,
        val color: Long? = null, val fontSize: Float? = null,
        val superscript: Boolean = false, val subscript: Boolean = false,
        val fontFamily: String? = null
    )

    private data class ParaStyleDef(
        val alignment: TextAlign? = null,
        val marginLeft: Float = 0f,
        val marginTop: Float = 0f,
        val marginBottom: Float = 0f,
        val textIndent: Float = 0f,
        val lineHeightPercent: Float? = null,
        val borderColor: Long? = null,
        val backgroundColor: Long? = null,
        val breakBefore: Boolean = false,
        val tabStops: List<Float> = emptyList()
    )

    private data class CellStyleDef(
        val backgroundColor: Long? = null, val textColor: Long? = null,
        val bold: Boolean = false, val italic: Boolean = false,
        val alignment: TextAlign? = null, val borderColor: Long? = null,
        val wrap: Boolean = false, val numberFormat: OdfNumberFormat? = null
    )

    private data class GraphicStyleDef(
        val fillColor: Long? = null, val strokeColor: Long? = null, val strokeWidth: Float? = null,
        val clip: String? = null
    )

    private fun getOrCreateGraphicStyle(fillColor: Long?, strokeColor: Long?, strokeWidth: Float?, clip: String?, styles: MutableMap<String, GraphicStyleDef>): String? {
        if (fillColor == null && strokeColor == null && strokeWidth == null && clip == null) return null
        val def = GraphicStyleDef(fillColor, strokeColor, strokeWidth, clip)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "gr${styles.size + 1}"
        styles[name] = def
        return name
    }

    private fun getOrCreateDrawPageStyle(fillColor: Long, styles: MutableMap<String, Long>): String {
        for ((name, existing) in styles) if (existing == fillColor) return name
        val name = "dp${styles.size + 1}"
        styles[name] = fillColor
        return name
    }

    private fun getOrCreateCellStyle(cell: OdfCell, styles: MutableMap<String, CellStyleDef>): String? {
        if (cell.backgroundColor == null && cell.textColor == null && !cell.bold && !cell.italic &&
            cell.alignment == null && cell.borderColor == null && !cell.wrap && cell.numberFormat == null) return null
        val def = CellStyleDef(cell.backgroundColor, cell.textColor, cell.bold, cell.italic, cell.alignment, cell.borderColor, cell.wrap, cell.numberFormat)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "ce${styles.size + 1}"
        styles[name] = def
        return name
    }

    private fun getOrCreateColStyle(width: Float, styles: MutableMap<String, Float>): String {
        for ((name, existing) in styles) if (existing == width) return name
        val name = "co${styles.size + 1}"
        styles[name] = width
        return name
    }

    private fun getOrCreateSpanStyle(span: OdfSpan, styles: MutableMap<String, SpanStyleDef>): String {
        val def = SpanStyleDef(span.bold, span.italic, span.underline, span.strikethrough,
            span.color, span.fontSize, span.superscript, span.subscript, span.fontFamily)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "T${styles.size + 1}"
        styles[name] = def
        return name
    }

    private fun getOrCreateParaStyle(para: OdfParagraph, styles: MutableMap<String, ParaStyleDef>): String? {
        val hasProps = para.alignment != null || para.marginLeft != 0f || para.textIndent != 0f ||
            para.lineHeightPercent != null || para.borderColor != null || para.backgroundColor != null ||
            para.marginTop != 0f || para.marginBottom != 0f || para.tabStops.isNotEmpty()
        if (!hasProps && !para.breakBeforePage) return null
        val def = ParaStyleDef(para.alignment, para.marginLeft, para.marginTop, para.marginBottom, para.textIndent,
            para.lineHeightPercent, para.borderColor, para.backgroundColor, para.breakBeforePage, para.tabStops)
        for ((name, existing) in styles) if (existing == def) return name
        val name = "P${styles.size + 1}"
        styles[name] = def
        return name
    }

    // --- XML construction ---

    private fun buildDocument(
        bodyType: String,
        spanStyles: Map<String, SpanStyleDef>,
        paraStyles: Map<String, ParaStyleDef>,
        cellStyles: Map<String, CellStyleDef>,
        colStyles: Map<String, Float>,
        graphicStyles: Map<String, GraphicStyleDef>,
        bodyContent: String,
        drawPageStyles: Map<String, Long> = emptyMap()
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<office:document-content""")
        sb.append(""" xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"""")
        sb.append(""" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"""")
        sb.append(""" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"""")
        sb.append(""" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"""")
        sb.append(""" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"""")
        sb.append(""" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"""")
        sb.append(""" xmlns:xlink="http://www.w3.org/1999/xlink"""")
        sb.append(""" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"""")
        sb.append(""" xmlns:dc="http://purl.org/dc/elements/1.1/"""")
        sb.append(""" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"""")
        sb.append(""" xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0"""")
        sb.append(""" office:version="1.3">""")

        sb.append("<office:automatic-styles>")
        for ((name, def) in spanStyles) {
            sb.append("""<style:style style:name="$name" style:family="text"><style:text-properties""")
            if (def.bold) sb.append(""" fo:font-weight="bold"""")
            if (def.italic) sb.append(""" fo:font-style="italic"""")
            if (def.underline) sb.append(""" style:text-underline-style="solid" style:text-underline-width="auto"""")
            if (def.strikethrough) sb.append(""" style:text-line-through-style="solid"""")
            if (def.color != null) sb.append(""" fo:color="${formatColor(def.color)}"""")
            if (def.fontSize != null) sb.append(""" fo:font-size="${def.fontSize}pt"""")
            if (def.fontFamily != null) sb.append(""" style:font-name="${esc(def.fontFamily)}"""")
            if (def.superscript) sb.append(""" style:text-position="super 58%"""")
            if (def.subscript) sb.append(""" style:text-position="sub 58%"""")
            sb.append("/></style:style>")
        }
        for ((name, def) in paraStyles) {
            sb.append("""<style:style style:name="$name" style:family="paragraph"><style:paragraph-properties""")
            when (def.alignment) {
                TextAlign.Start, TextAlign.Left -> sb.append(""" fo:text-align="start"""")
                TextAlign.Center -> sb.append(""" fo:text-align="center"""")
                TextAlign.End, TextAlign.Right -> sb.append(""" fo:text-align="end"""")
                TextAlign.Justify -> sb.append(""" fo:text-align="justify"""")
                else -> {}
            }
            if (def.marginLeft != 0f) sb.append(""" fo:margin-left="${cm(def.marginLeft)}"""")
            if (def.marginTop != 0f) sb.append(""" fo:margin-top="${cm(def.marginTop)}"""")
            if (def.marginBottom != 0f) sb.append(""" fo:margin-bottom="${cm(def.marginBottom)}"""")
            if (def.textIndent != 0f) sb.append(""" fo:text-indent="${cm(def.textIndent)}"""")
            if (def.lineHeightPercent != null) sb.append(""" fo:line-height="${(def.lineHeightPercent * 100).toInt()}%"""")
            if (def.borderColor != null) sb.append(""" fo:border="0.5pt solid ${formatColor(def.borderColor)}"""")
            if (def.backgroundColor != null) sb.append(""" fo:background-color="${formatColor(def.backgroundColor)}"""")
            if (def.breakBefore) sb.append(""" fo:break-before="page"""")
            if (def.tabStops.isNotEmpty()) {
                sb.append("><style:tab-stops>")
                for (t in def.tabStops) sb.append("""<style:tab-stop style:position="${cm(t)}"/>""")
                sb.append("</style:tab-stops>")
                sb.append("</style:paragraph-properties></style:style>")
                continue
            }
            sb.append("/></style:style>")
        }
        for ((name, width) in colStyles) {
            sb.append("""<style:style style:name="$name" style:family="table-column"><style:table-column-properties style:column-width="${cm(width)}"/></style:style>""")
        }
        // Number/date/currency data styles for cells, emitted before the cell styles that reference them. (B6)
        val dataStyleNames = HashMap<String, String>()
        run {
            var n = 0
            for ((cellName, def) in cellStyles) {
                val fmt = def.numberFormat ?: continue
                n++
                val dsName = "N$n"
                dataStyleNames[cellName] = dsName
                sb.append(numberStyleXml(dsName, fmt))
            }
        }
        for ((name, def) in cellStyles) {
            sb.append("""<style:style style:name="$name" style:family="table-cell"""")
            dataStyleNames[name]?.let { sb.append(""" style:data-style-name="$it"""") }
            sb.append(">")
            sb.append("<style:table-cell-properties")
            if (def.backgroundColor != null) sb.append(""" fo:background-color="${formatColor(def.backgroundColor)}"""")
            if (def.borderColor != null) sb.append(""" fo:border="0.5pt solid ${formatColor(def.borderColor)}"""")
            if (def.wrap) sb.append(""" fo:wrap-option="wrap"""")
            sb.append("/>")
            when (def.alignment) {
                TextAlign.Start, TextAlign.Left -> sb.append("""<style:paragraph-properties fo:text-align="start"/>""")
                TextAlign.Center -> sb.append("""<style:paragraph-properties fo:text-align="center"/>""")
                TextAlign.End, TextAlign.Right -> sb.append("""<style:paragraph-properties fo:text-align="end"/>""")
                TextAlign.Justify -> sb.append("""<style:paragraph-properties fo:text-align="justify"/>""")
                else -> {}
            }
            sb.append("<style:text-properties")
            if (def.bold) sb.append(""" fo:font-weight="bold"""")
            if (def.italic) sb.append(""" fo:font-style="italic"""")
            if (def.textColor != null) sb.append(""" fo:color="${formatColor(def.textColor)}"""")
            sb.append("/></style:style>")
        }
        for ((name, def) in graphicStyles) {
            sb.append("""<style:style style:name="$name" style:family="graphic"><style:graphic-properties""")
            if (def.fillColor != null) sb.append(""" draw:fill="solid" draw:fill-color="${formatColor(def.fillColor)}"""")
            else sb.append(""" draw:fill="none"""")
            if (def.strokeColor != null) sb.append(""" draw:stroke="solid" svg:stroke-color="${formatColor(def.strokeColor)}"""")
            if (def.strokeWidth != null) sb.append(""" svg:stroke-width="${cm(def.strokeWidth)}"""")
            if (def.clip != null) sb.append(""" fo:clip="${def.clip}"""")
            sb.append("/></style:style>")
        }
        for ((name, color) in drawPageStyles) {
            sb.append("""<style:style style:name="$name" style:family="drawing-page"><style:drawing-page-properties draw:fill="solid" draw:fill-color="${formatColor(color)}"/></style:style>""")
        }
        sb.append("</office:automatic-styles>")

        sb.append("<office:body><$bodyType>")
        sb.append(bodyContent)
        sb.append("</$bodyType></office:body>")
        sb.append("</office:document-content>")
        return sb.toString()
    }

    private fun numberStyleXml(name: String, fmt: OdfNumberFormat): String {
        val sb = StringBuilder()
        val dec = fmt.decimals ?: 2
        when {
            fmt.isDate -> {
                sb.append("""<number:date-style style:name="$name">""")
                sb.append("""<number:year number:style="long"/><number:text>-</number:text>""")
                sb.append("""<number:month number:style="long"/><number:text>-</number:text>""")
                sb.append("""<number:day number:style="long"/>""")
                sb.append("</number:date-style>")
            }
            fmt.currencySymbol != null -> {
                sb.append("""<number:currency-style style:name="$name">""")
                sb.append("""<number:currency-symbol>${esc(fmt.currencySymbol)}</number:currency-symbol>""")
                sb.append("""<number:number number:decimal-places="$dec" number:min-integer-digits="1"${if (fmt.grouping) " number:grouping=\"true\"" else ""}/>""")
                sb.append("</number:currency-style>")
            }
            fmt.percent -> {
                sb.append("""<number:percentage-style style:name="$name">""")
                sb.append("""<number:number number:decimal-places="$dec" number:min-integer-digits="1"/>""")
                sb.append("""<number:text>%</number:text>""")
                sb.append("</number:percentage-style>")
            }
            else -> {
                sb.append("""<number:number-style style:name="$name">""")
                sb.append("""<number:number number:decimal-places="$dec" number:min-integer-digits="1"${if (fmt.grouping) " number:grouping=\"true\"" else ""}/>""")
                sb.append("</number:number-style>")
            }
        }
        return sb.toString()
    }

    private fun formatColor(color: Long): String {
        val rgb = (color and 0xFFFFFFL).toInt()
        return String.format("#%06X", rgb)
    }

    /** px@96 -> cm length string. */
    private fun cm(px: Float): String = String.format(Locale.US, "%.4fcm", px / 37.795f)

    private fun sniffExt(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "png"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> "gif"
        else -> "png"
    }

    private fun mediaTypeForImage(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
