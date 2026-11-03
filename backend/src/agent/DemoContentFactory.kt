package com.pgigi.agentclouddisk.agent

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 造样本文件用：真实可解析的 docx / xlsx / txt / png */
object DemoContentFactory {

    fun text(content: String): ByteArray = content.toByteArray(Charsets.UTF_8)

    fun docx(paragraphs: List<String>): ByteArray {
        val body = paragraphs.joinToString("") {
            "<w:p><w:r><w:t xml:space=\"preserve\">" + escapeXml(it) + "</w:t></w:r></w:p>"
        }
        val document = header + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>" + body + "</w:body></w:document>"
        val contentTypes = header + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"
        val rels = header + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
        return zipOf(listOf("[Content_Types].xml" to contentTypes, "_rels/.rels" to rels, "word/document.xml" to document))
    }

    fun xlsx(headers: List<String>, rows: List<List<String>>): ByteArray {
        val shared = ArrayList<String>()
        fun index(value: String): Int {
            val existing = shared.indexOf(value)
            if (existing >= 0) return existing
            shared.add(value)
            return shared.size - 1
        }

        val sheetRows = StringBuilder()
        val allRows = listOf(headers) + rows
        allRows.forEachIndexed { rowIndex, cells ->
            sheetRows.append("<row r=\"").append(rowIndex + 1).append("\">")
            cells.forEachIndexed { colIndex, cell ->
                val ref = columnName(colIndex) + (rowIndex + 1)
                sheetRows.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(index(cell)).append("</v></c>")
            }
            sheetRows.append("</row>")
        }

        val sheet = header + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<sheetData>" + sheetRows + "</sheetData></worksheet>"
        val sharedXml = header + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"" +
            shared.size + "\" uniqueCount=\"" + shared.size + "\">" +
            shared.joinToString("") { "<si><t>" + escapeXml(it) + "</t></si>" } + "</sst>"
        val workbook = header + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        val workbookRels = header + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>" +
            "</Relationships>"
        val contentTypes = header + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>" +
            "</Types>"
        return zipOf(
            listOf(
                "[Content_Types].xml" to contentTypes,
                "_rels/.rels" to (header + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"),
                "xl/workbook.xml" to workbook,
                "xl/_rels/workbook.xml.rels" to workbookRels,
                "xl/worksheets/sheet1.xml" to sheet,
                "xl/sharedStrings.xml" to sharedXml,
            )
        )
    }

    fun png(): ByteArray = Base64.getDecoder().decode(PNG_BASE64)

    fun pdfStub(): ByteArray = ("%PDF-1.4" + System.lineSeparator() +
        "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj" + System.lineSeparator() +
        "2 0 obj<</Type/Pages/Count 0/Kids[]>>endobj" + System.lineSeparator() +
        "trailer<</Root 1 0 R>>" + System.lineSeparator() + "%%EOF").toByteArray(Charsets.UTF_8)

    fun zipStub(): ByteArray = zipOf(listOf("readme.txt" to "爬山照片合集（占位）"))

    private fun zipOf(entries: List<Pair<String, String>>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { pair ->
                zip.putNextEntry(ZipEntry(pair.first))
                zip.write(pair.second.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun columnName(index: Int): String {
        var value = index
        val name = StringBuilder()
        while (true) {
            name.insert(0, ('A' + value % 26))
            value = value / 26 - 1
            if (value < 0) break
        }
        return name.toString()
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private const val header = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private const val PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4AAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAALxSURBVHhe7dFVVhhBGEThXnbc3d3dg7u7RoEoFgECRIElVE7P3316egNTL/VwN/Bdh5ptKKrdHtoB1MV2AvW+XVbD7lTjHqtpb6p5n9Wy32o9kGo7GDoEtMcOAx2+I1bn0VTXMav7eKrnhNV70uo7leo/HToDDMTOAoO+c9bQ+dTwhdBFYCR2CRj1XbbGrqTGr1oT11KT160XN6yXN1OvboVuA69jd4A3vrvW23twwufh2wDh0/BLA4TPwMfUfT9A+Cx8GyB8Gj6mHsQBwmfgY7oYIHwWvg0QPg0f0w/9AOGz8DETBwifgo+ZR3DC5+HnA4RfOT7ePQ4DhE/BtwHCp+GnAcKn4OP9Ezjh8/DzAcKvHB8fnoYBwqfg2wDh0/DTAOFT8PHxGZzwefhpgPAp+Pj0HE74PPx8gPArx8fnmjBA+BR8GyB8Gn5pgPAZ+Jit9QOEz8K3AcKn4WO2Lg4QPgMfc8UA4bPwbYDwafiYq/cDhM/Cx3wcIHwKPuYb4ITPw88HCL9yfCw0hgHCp+DbAOHT8NMA4VPwsdgEJ3wefj5A+JXj40tzGCB8Cr4NED4NPw0QPgUfX1vghM/DTwOET8HHt1Y44fPw8wHCrxwf39vCAOFT8G2A8Gn4pQHCZ+Bjqd0PED4L3wYIn4aPpY44QPgMfCwXA4TPwrcBwqfhY7nTDxA+Cx8rcYDwKfhY6YITPg8/HyD8yvHxozsMED4F3wYIn4ZfGiB8Bj5We/wA4bPwbYDwafhY7Y0DhM/Ax1oxQPgsfBsgfBo+1vr8AOGz8LEeBwifgo/1fjjh8/DzAcKvHB8/B8IA4VPwbYDwafhpgPAp+Pg1CCd8Hn4+QPiV4+P3UBggfAq+DRA+DT8NED4FH3+G4YTPw08DhE/Bx98ROOHz8PMBwq8cH/9GwwDhU/BtgPBp+KUBwmfgY2PMDxA+C98GCJ+Gj43xOED4DHxsFgOEz8K3AcKn4WNzwg8QPgsfW3GA8Cn42JqEEz4P3/cfiW33N2Yh3YAAAAAASUVORK5CYII="
}
