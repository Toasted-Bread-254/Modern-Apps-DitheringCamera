package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetDocCodecTest {

    private fun sheet(vararg rows: List<String>) = OdfDocument.Spreadsheet(
        title = "s",
        sheets = listOf(OdfSheet("Sheet 1", rows.map { r -> OdfRow(r.map { OdfCell(text = it) }) }))
    )

    private fun cellTexts(doc: OdfDocument.Spreadsheet) = doc.sheets[0].rows.map { row -> row.cells.map { it.text } }

    @Test
    fun roundtrip_preserves_cells_and_style() {
        val doc = OdfDocument.Spreadsheet(
            title = "s",
            sheets = listOf(OdfSheet("Data", listOf(
                OdfRow(listOf(OdfCell(text = "Name", bold = true), OdfCell(text = "Total", formula = "=SUM(A1:A2)", valueType = "float"))),
                OdfRow(listOf(OdfCell(text = "x"), OdfCell(text = "5")))
            )))
        )
        val rebuilt = SheetDocCodec.fromCells(SheetDocCodec.toCells(doc), doc)
        assertEquals(cellTexts(doc), cellTexts(rebuilt))
        assertEquals("Data", rebuilt.sheets[0].name)
        assertTrue(rebuilt.sheets[0].rows[0].cells[0].bold)
        assertEquals("=SUM(A1:A2)", rebuilt.sheets[0].rows[0].cells[1].formula)
    }

    @Test
    fun concurrent_edits_same_cell_merge_char_level() {
        val base = sheet(listOf("Hello"))
        val a = DocumentCrdt("A"); val baseOps = a.update(SheetDocCodec.toCells(base))
        val b = DocumentCrdt("B"); b.apply(baseOps)
        val opsA = a.update(SheetDocCodec.toCells(sheet(listOf("Hello World")))) // append " World"
        val opsB = b.update(SheetDocCodec.toCells(sheet(listOf("Hi Hello"))))    // prepend "Hi "
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val merged = SheetDocCodec.fromCells(a.render(), base)
        val text = merged.sheets[0].rows[0].cells[0].text
        assertTrue("has A's edit", text.contains("World"))
        assertTrue("has B's edit", text.contains("Hi"))
        assertEquals("still one row", 1, merged.sheets[0].rows.size)
        assertEquals("still one cell (no duplication)", 1, merged.sheets[0].rows[0].cells.size)
    }

    @Test
    fun concurrent_new_rows_merge() {
        val base = sheet(listOf("r1"))
        val a = DocumentCrdt("A"); val baseOps = a.update(SheetDocCodec.toCells(base))
        val b = DocumentCrdt("B"); b.apply(baseOps)
        val opsA = a.update(SheetDocCodec.toCells(sheet(listOf("r1"), listOf("from-A"))))
        val opsB = b.update(SheetDocCodec.toCells(sheet(listOf("r1"), listOf("from-B"))))
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val merged = SheetDocCodec.fromCells(a.render(), base)
        val allText = merged.sheets[0].rows.flatMap { it.cells }.joinToString("|") { it.text }
        assertTrue(allText.contains("from-A"))
        assertTrue(allText.contains("from-B"))
        assertTrue(allText.contains("r1"))
    }

    @Test
    fun ineligible_when_merged_cells_or_images() {
        assertTrue(SheetDocCodec.isEligible(sheet(listOf("a"))))
        val merged = OdfDocument.Spreadsheet(title = "s", sheets = listOf(OdfSheet("S", listOf(OdfRow(listOf(OdfCell(text = "a", spannedColumns = 2)))))))
        assertTrue(!SheetDocCodec.isEligible(merged))
    }
}
