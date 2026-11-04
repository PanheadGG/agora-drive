package com.pgigi.agentclouddisk.agent

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * 造演示样本文件，全部是能真正打开的文件：
 * docx / xlsx 是标准 OOXML 包，pdf 是带 xref 的文本型 PDF，png 用 Java2D 真实绘制并带可 OCR 的文字，zip 内含真实图片。
 */
object DemoContentFactory {

    init {
        System.setProperty("java.awt.headless", "true")
    }

    fun text(content: String): ByteArray = content.toByteArray(Charsets.UTF_8)

    fun docx(paragraphs: List<String>): ByteArray {
        val body = paragraphs.joinToString("") {
            "<w:p><w:r><w:t xml:space=\"preserve\">" + escapeXml(it) + "</w:t></w:r></w:p>"
        }
        val document = XML_HEADER + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>" + body + "</w:body></w:document>"
        val contentTypes = XML_HEADER + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"
        val rels = XML_HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
        return zipOf(
            "[Content_Types].xml" to contentTypes.toByteArray(Charsets.UTF_8),
            "_rels/.rels" to rels.toByteArray(Charsets.UTF_8),
            "word/document.xml" to document.toByteArray(Charsets.UTF_8),
        )
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
        (listOf(headers) + rows).forEachIndexed { rowIndex, cells ->
            sheetRows.append("<row r=\"").append(rowIndex + 1).append("\">")
            cells.forEachIndexed { colIndex, cell ->
                sheetRows.append("<c r=\"").append(columnName(colIndex)).append(rowIndex + 1)
                    .append("\" t=\"s\"><v>").append(index(cell)).append("</v></c>")
            }
            sheetRows.append("</row>")
        }

        val sheet = XML_HEADER + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<sheetData>" + sheetRows + "</sheetData></worksheet>"
        val sharedXml = XML_HEADER + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"" +
            shared.size + "\" uniqueCount=\"" + shared.size + "\">" +
            shared.joinToString("") { "<si><t>" + escapeXml(it) + "</t></si>" } + "</sst>"
        val workbook = XML_HEADER + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        val workbookRels = XML_HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>" +
            "</Relationships>"
        val rootRels = XML_HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"
        val contentTypes = XML_HEADER + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>" +
            "</Types>"
        return zipOf(
            "[Content_Types].xml" to contentTypes.toByteArray(Charsets.UTF_8),
            "_rels/.rels" to rootRels.toByteArray(Charsets.UTF_8),
            "xl/workbook.xml" to workbook.toByteArray(Charsets.UTF_8),
            "xl/_rels/workbook.xml.rels" to workbookRels.toByteArray(Charsets.UTF_8),
            "xl/worksheets/sheet1.xml" to sheet.toByteArray(Charsets.UTF_8),
            "xl/sharedStrings.xml" to sharedXml.toByteArray(Charsets.UTF_8),
        )
    }

    /** 真实绘制的 PNG，画面里带可 OCR 的文字 */
    fun photo(title: String, subtitle: String, tint: Int): ByteArray {
        val width = 720
        val height = 480
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val base = Color(tint)
        graphics.paint = GradientPaint(0f, 0f, base.brighter(), width.toFloat(), height.toFloat(), base.darker())
        graphics.fillRect(0, 0, width, height)

        graphics.color = Color(255, 255, 255, 70)
        graphics.fillOval(width / 2 - 150, height / 2 - 130, 300, 300)
        graphics.color = Color(255, 255, 255, 45)
        graphics.fillRoundRect(40, height - 200, width - 80, 160, 24, 24)

        graphics.color = Color.WHITE
        graphics.font = uiFont(Font.BOLD, 46)
        graphics.drawString(title, 48, 120)
        graphics.font = uiFont(Font.PLAIN, 30)
        graphics.drawString(subtitle, 48, 182)
        graphics.font = uiFont(Font.PLAIN, 24)
        graphics.drawString("my own photo, kept in personal netdisk", 48, height - 60)
        graphics.dispose()

        val buffer = ByteArrayOutputStream()
        ImageIO.write(image, "png", buffer)
        return buffer.toByteArray()
    }

    /** 文本型 PDF（未压缩内容流 + 正确 xref），可被 PdfTextExtractor 抽取 */
    fun pdf(title: String, lines: List<String>): ByteArray {
        val content = StringBuilder()
        content.append("BT /F1 20 Tf 60 760 Td (").append(escapePdf(title)).append(") Tj ET\n")
        var y = 720
        for (line in lines) {
            content.append("BT /F1 12 Tf 60 ").append(y).append(" Td (").append(escapePdf(line)).append(") Tj ET\n")
            y -= 26
        }
        val contentBytes = content.toString().toByteArray(Charsets.ISO_8859_1)

        val objects = ArrayList<ByteArray>()
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.ISO_8859_1))
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(Charsets.ISO_8859_1))
        objects.add(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"
                .toByteArray(Charsets.ISO_8859_1)
        )
        val streamHeader = ("<< /Length " + contentBytes.size + " >>\nstream\n").toByteArray(Charsets.ISO_8859_1)
        val streamFooter = "\nendstream".toByteArray(Charsets.ISO_8859_1)
        val streamObject = ByteArray(streamHeader.size + contentBytes.size + streamFooter.size)
        System.arraycopy(streamHeader, 0, streamObject, 0, streamHeader.size)
        System.arraycopy(contentBytes, 0, streamObject, streamHeader.size, contentBytes.size)
        System.arraycopy(streamFooter, 0, streamObject, streamHeader.size + contentBytes.size, streamFooter.size)
        objects.add(streamObject)
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".toByteArray(Charsets.ISO_8859_1))

        val output = ByteArrayOutputStream()
        output.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { index, bytes ->
            offsets.add(output.size())
            output.write((index + 1).toString().toByteArray(Charsets.ISO_8859_1))
            output.write(" 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            output.write(bytes)
            output.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xrefOffset = output.size()
        output.write("xref\n0 ".toByteArray(Charsets.ISO_8859_1))
        output.write((objects.size + 1).toString().toByteArray(Charsets.ISO_8859_1))
        output.write("\n0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        offsets.forEach { offset ->
            output.write(String.format("%010d 00000 n \n", offset).toByteArray(Charsets.ISO_8859_1))
        }
        output.write(
            ("trailer\n<< /Size " + (objects.size + 1) + " /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF\n")
                .toByteArray(Charsets.ISO_8859_1)
        )
        return output.toByteArray()
    }

    fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { pair ->
                zip.putNextEntry(ZipEntry(pair.first))
                zip.write(pair.second)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun uiFont(style: Int, size: Int): Font {
        val candidates = listOf("Microsoft YaHei", "SimHei", "SansSerif")
        val available = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val family = candidates.firstOrNull { available.contains(it) } ?: Font.SANS_SERIF
        return Font(family, style, size)
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

    private fun escapePdf(value: String): String = value
        .replace("\\", "\\\\")
        .replace("(", "\\(")
        .replace(")", "\\)")

    private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
}
