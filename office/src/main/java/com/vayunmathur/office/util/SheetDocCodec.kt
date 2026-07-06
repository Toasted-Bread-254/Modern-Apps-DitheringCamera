package com.vayunmathur.office.util

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Projects an [OdfDocument.Spreadsheet] to/from a flat list of cells so [DocumentCrdt] merges cell
 * *text* at character granularity (two people editing the same cell converge without clobbering).
 *
 * Layout per sheet: the characters of each cell, a `C` terminator carrying the cell's non-text
 * attributes (formula/style), an `R` terminator per row, and an `S` terminator per sheet carrying the
 * sheet's layout. Only spreadsheets whose content the codec can fully represent are eligible (see
 * [isEligible]); anything richer (images, merged cells, borders, floating objects) uses the lossless
 * line-level codec.
 */
object SheetDocCodec {
    private const val SEP = '\u0002'
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class CellMeta(
        val formula: String? = null, val valueType: String? = null,
        val bold: Boolean = false, val italic: Boolean = false,
        val textColor: Long? = null, val bg: Long? = null, val align: String? = null,
    )

    @Serializable
    data class SheetMeta(
        val name: String,
        val colWidths: List<Float?> = emptyList(),
        val rowHeights: List<Float?> = emptyList(),
        val freezeRows: Int = 0, val freezeCols: Int = 0,
        val hidden: Boolean = false, val tabColor: Long? = null,
    )

    fun isEligible(doc: OdfDocument): Boolean {
        if (doc !is OdfDocument.Spreadsheet) return false
        if (doc.images.isNotEmpty() || doc.namedRanges.isNotEmpty() || doc.validations.isNotEmpty()) return false
        for (sheet in doc.sheets) {
            if (sheet.floating.isNotEmpty()) return false
            for (row in sheet.rows) for (c in row.cells) {
                if (c.borders != null || c.isCovered || c.spannedColumns != 1 || c.rowSpan != 1) return false
            }
        }
        return true
    }

    fun toCells(doc: OdfDocument.Spreadsheet): List<String> {
        val cells = ArrayList<String>()
        for (sheet in doc.sheets) {
            for (row in sheet.rows) {
                for (cell in row.cells) {
                    for (ch in cell.text) cells.add("c$SEP$ch")
                    cells.add("C$SEP${json.encodeToString(cellMeta(cell))}")
                }
                cells.add("R$SEP")
            }
            cells.add("S$SEP${json.encodeToString(sheetMeta(sheet))}")
        }
        return cells
    }

    fun fromCells(cells: List<String>, base: OdfDocument.Spreadsheet): OdfDocument.Spreadsheet {
        val sheets = ArrayList<OdfSheet>()
        var rows = ArrayList<OdfRow>()
        var rowCells = ArrayList<OdfCell>()
        val text = StringBuilder()
        for (cell in cells) {
            val sep = cell.indexOf(SEP)
            if (sep < 0) continue
            val kind = cell.substring(0, sep)
            val value = cell.substring(sep + 1)
            when (kind) {
                "c" -> text.append(value)
                "C" -> {
                    val m = runCatching { json.decodeFromString<CellMeta>(value) }.getOrNull() ?: CellMeta()
                    rowCells.add(OdfCell(
                        text = text.toString(), formula = m.formula, valueType = m.valueType,
                        bold = m.bold, italic = m.italic, textColor = m.textColor, backgroundColor = m.bg,
                        alignment = parseAlign(m.align),
                    ))
                    text.clear()
                }
                "R" -> { rows.add(OdfRow(rowCells)); rowCells = ArrayList() }
                "S" -> {
                    val m = runCatching { json.decodeFromString<SheetMeta>(value) }.getOrNull() ?: SheetMeta("Sheet")
                    sheets.add(OdfSheet(
                        name = m.name, rows = rows, columnWidths = m.colWidths, rowHeights = m.rowHeights,
                        freezeRows = m.freezeRows, freezeCols = m.freezeCols, hidden = m.hidden, tabColor = m.tabColor,
                    ))
                    rows = ArrayList()
                }
            }
        }
        if (sheets.isEmpty()) sheets.add(OdfSheet("Sheet 1", listOf(OdfRow(listOf(OdfCell(text = ""))))))
        return base.copy(sheets = sheets)
    }

    private fun cellMeta(c: OdfCell) = CellMeta(
        formula = c.formula, valueType = c.valueType, bold = c.bold, italic = c.italic,
        textColor = c.textColor, bg = c.backgroundColor, align = c.alignment?.let { alignName(it) },
    )

    private fun sheetMeta(s: OdfSheet) = SheetMeta(
        name = s.name, colWidths = s.columnWidths, rowHeights = s.rowHeights,
        freezeRows = s.freezeRows, freezeCols = s.freezeCols, hidden = s.hidden, tabColor = s.tabColor,
    )

    private fun alignName(a: TextAlign): String = when (a) {
        TextAlign.Start -> "start"; TextAlign.End -> "end"; TextAlign.Center -> "center"
        TextAlign.Justify -> "justify"; TextAlign.Left -> "left"; TextAlign.Right -> "right"; else -> "start"
    }

    private fun parseAlign(s: String?): TextAlign? = when (s) {
        "start" -> TextAlign.Start; "end" -> TextAlign.End; "center" -> TextAlign.Center
        "justify" -> TextAlign.Justify; "left" -> TextAlign.Left; "right" -> TextAlign.Right; else -> null
    }
}
