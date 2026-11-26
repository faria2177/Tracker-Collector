package com.trackercollector.app.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One row of the exported list. */
data class ExportRow(val serial: String, val name: String, val designation: String, val mobile: String)

/**
 * Writes real .xlsx and .docx files using plain Office Open XML (no Apache POI needed,
 * so the APK stays small and works on every Android version).
 *
 * Layout matches the reference sheet: SL | Name of Customer | Customer Designation | Mobile Number
 */
object OfficeExporter {

    val HEADERS = listOf("SL", "Name of Customer", "Customer Designation", "Mobile Number")

    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (c.code >= 0x20 || c == '\t' || c == '\n') append(c)
        }
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    // ============================================================== XLSX

    fun writeXlsx(rows: List<ExportRow>, out: OutputStream, sheetName: String = "Customers") {
        ZipOutputStream(out).use { z ->
            z.put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""")
            z.put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")
            z.put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${esc(sheetName)}" sheetId="1" r:id="rId1"/></sheets>
</workbook>""")
            z.put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""")
            // Styles: 0 default, 1 header (bold, grey fill, border, centred),
            // 2 text with border, 3 centred text with border.
            z.put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF2F2F2"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="49" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
<xf numFmtId="49" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center"/></xf>
<xf numFmtId="49" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyNumberFormat="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>""")

            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
<sheetFormatPr defaultRowHeight="18"/>
<cols><col min="1" max="1" width="7" customWidth="1"/><col min="2" max="2" width="36" customWidth="1"/><col min="3" max="3" width="30" customWidth="1"/><col min="4" max="4" width="18" customWidth="1"/></cols>
<sheetData>""")
            val letters = listOf("A", "B", "C", "D")
            fun cell(ref: String, value: String, style: Int) =
                """<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${esc(value)}</t></is></c>"""

            sb.append("<row r=\"1\" ht=\"22\" customHeight=\"1\">")
            HEADERS.forEachIndexed { i, hd -> sb.append(cell("${letters[i]}1", hd, 1)) }
            sb.append("</row>")
            rows.forEachIndexed { idx, r ->
                val n = idx + 2
                sb.append("<row r=\"$n\">")
                sb.append(cell("A$n", r.serial, 3))
                sb.append(cell("B$n", r.name, 2))
                sb.append(cell("C$n", r.designation, 2))
                sb.append(cell("D$n", r.mobile, 3))
                sb.append("</row>")
            }
            sb.append("</sheetData>")
            sb.append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
            sb.append("</worksheet>")
            z.put("xl/worksheets/sheet1.xml", sb.toString())
        }
    }

    // ============================================================== DOCX

    fun writeDocx(rows: List<ExportRow>, out: OutputStream, title: String = "Customer List") {
        ZipOutputStream(out).use { z ->
            z.put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""")
            z.put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""")

            // Widths in twentieths of a point; total = 9638 (A4 minus 2 cm margins).
            val widths = listOf(800, 3700, 3238, 1900)
            val font = """<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/>"""

            fun para(text: String, bold: Boolean, center: Boolean, size: Int = 22) =
                "<w:p><w:pPr><w:spacing w:before=\"40\" w:after=\"40\"/>" +
                    (if (center) "<w:jc w:val=\"center\"/>" else "") +
                    "</w:pPr><w:r><w:rPr>$font" + (if (bold) "<w:b/>" else "") +
                    "<w:sz w:val=\"$size\"/></w:rPr><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r></w:p>"

            fun tc(text: String, w: Int, bold: Boolean, center: Boolean, shade: Boolean) =
                "<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>" +
                    (if (shade) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>" else "") +
                    "<w:vAlign w:val=\"center\"/></w:tcPr>${para(text, bold, center)}</w:tc>"

            val border = "w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\""
            val sb = StringBuilder()
            sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            sb.append(para(title, bold = true, center = true, size = 28))
            sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"${widths.sum()}\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/>")
            sb.append("<w:tblBorders><w:top $border/><w:left $border/><w:bottom $border/><w:right $border/><w:insideH $border/><w:insideV $border/></w:tblBorders>")
            sb.append("<w:tblCellMar><w:left w:w=\"100\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr>")
            sb.append("<w:tblGrid>")
            widths.forEach { sb.append("<w:gridCol w:w=\"$it\"/>") }
            sb.append("</w:tblGrid>")

            sb.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>")
            HEADERS.forEachIndexed { i, hd -> sb.append(tc(hd, widths[i], bold = true, center = true, shade = true)) }
            sb.append("</w:tr>")
            for (r in rows) {
                sb.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
                sb.append(tc(r.serial, widths[0], false, true, false))
                sb.append(tc(r.name, widths[1], false, false, false))
                sb.append(tc(r.designation, widths[2], false, false, false))
                sb.append(tc(r.mobile, widths[3], false, true, false))
                sb.append("</w:tr>")
            }
            sb.append("</w:tbl>")
            sb.append("<w:p/>")
            sb.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/></w:sectPr>")
            sb.append("</w:body></w:document>")
            z.put("word/document.xml", sb.toString())
        }
    }
}
