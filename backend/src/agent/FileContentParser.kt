package com.pgigi.agentclouddisk.agent

import com.alibaba.excel.EasyExcel
import com.alibaba.excel.context.AnalysisContext
import com.alibaba.excel.event.AnalysisEventListener
import com.pgigi.agentclouddisk.service.StorageService
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * 真实内容解析：只用文件本身的内容判定，不看文件名。
 *
 * 全部采用流式处理，避免整份文件读进内存：
 * - 表格 xlsx：EasyExcel 逐行读取，边读边做敏感信息检测，只保留表头 + 前若干行做预览
 * - 文档 docx：StAX 流式解析 word/document.xml，逐段喂给扫描器
 * - 文本 / CSV：按字符块流式读取
 * - PDF：抽取文本后分块扫描
 * - 图片：Windows OCR
 * - 压缩包：受控解包后逐项流式扫描
 */
@Service
class FileContentParser(
    private val storage: StorageService,
    private val ocr: OcrService,
) {

    data class ParsedFile(
        val ok: Boolean,
        val method: String,
        val text: String,
        val summary: String,
        val note: String? = null,
        val risk: RiskDetector.RiskInfo? = null,
        val copied: Boolean = false,
        val copiedMarkers: List<String> = emptyList(),
        val previewText: String = "",
        val previewKind: String = "text",
        val truncated: Boolean = false,
    )

    fun parse(sha256: String?, fileName: String, size: Long): ParsedFile {
        if (sha256.isNullOrBlank()) return fail("文件元数据缺失，无法定位存储内容")
        val path = storage.contentPath(sha256)
        if (!Files.exists(path)) return fail("存储中找不到文件内容，可能已被清理")
        if (size > MAX_SIZE) return fail("文件超过 Demo 解析上限（10MB），跳过")

        val ext = fileName.substringAfterLast('.', "").lowercase()
        return try {
            when (ext) {
                in TEXT_EXTS -> parseText(path)
                "docx" -> parseDocx(path)
                "xlsx", "xls" -> parseXlsx(path)
                "pdf" -> parsePdf(path, size)
                "zip" -> parseZip(path)
                in IMAGE_EXTS -> parseImage(path)
                else -> fail("暂不支持解析该格式（." + ext + "）")
            }
        } catch (error: StackOverflowError) {
            // 例如 PDF 里的畸形/超大二进制流导致递归回溯
            fail("文件内部结构异常（解析栈溢出），已跳过该文件，不影响其他文件")
        } catch (error: OutOfMemoryError) {
            fail("文件过大导致内存不足，已跳过该文件")
        } catch (error: Throwable) {
            fail("解析异常：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    // ---------- 文本 ----------

    private fun parseText(path: Path): ParsedFile {
        val scanner = StreamingSensitiveScanner()
        val text = StringBuilder()
        Files.newBufferedReader(path, Charsets.UTF_8).use { reader ->
            val buffer = CharArray(CHUNK)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                val chunk = String(buffer, 0, read).replace("\u0000", "")
                scanner.feed(chunk)
                if (text.length < MAX_TEXT) {
                    text.append(chunk, 0, minOf(chunk.length, MAX_TEXT - text.length))
                }
            }
        }
        return build("抽正文（流式）", text.toString(), scanner, "text")
    }

    // ---------- Word ----------

    private fun parseDocx(path: Path): ParsedFile {
        ZipFile(path.toFile()).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return fail("docx 结构不完整，缺少正文")
            zip.getInputStream(entry).use { input -> return parseDocxStream(input) }
        }
    }

    private fun parseDocxStream(input: InputStream): ParsedFile {
        val scanner = StreamingSensitiveScanner()
        val text = StringBuilder()
        val factory = XMLInputFactory.newInstance()
        factory.setProperty(XMLInputFactory.IS_COALESCING, true)
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)

        val reader = factory.createXMLStreamReader(input)
        var inText = false
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> inText = reader.localName == "t"
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (inText) {
                        val chunk = reader.text ?: continue
                        scanner.feed(chunk)
                        if (text.length < MAX_TEXT) {
                            text.append(chunk, 0, minOf(chunk.length, MAX_TEXT - text.length))
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> if (reader.localName == "p") {
                        scanner.feed("\n")
                        if (text.length < MAX_TEXT) text.append('\n')
                    }
                }
            }
        } finally {
            runCatching { reader.close() }
        }
        return build("抽正文（流式）", text.toString(), scanner, "text")
    }

    // ---------- Excel（EasyExcel 流式） ----------

    private fun parseXlsx(path: Path): ParsedFile {
        val scanner = StreamingSensitiveScanner(maxPreview = 0)
        val preview = StringBuilder()
        var rowCount = 0
        var header: List<String> = emptyList()

        Files.newInputStream(path).use { input ->
            val listener = object : AnalysisEventListener<Map<Int, String?>>() {
                override fun invokeHeadMap(headMap: Map<Int, String?>?, context: AnalysisContext?) {
                    if (headMap == null) return
                    header = headMap.entries.sortedBy { it.key }.map { it.value ?: "" }
                    scanner.feedLine(header.joinToString(" | "))
                    preview.append(header.joinToString(" | ")).append('\n')
                }

                override fun invoke(data: Map<Int, String?>?, context: AnalysisContext?) {
                    if (data == null) return
                    val line = data.entries.sortedBy { it.key }.joinToString(" | ") { it.value ?: "" }
                    if (line.isBlank()) return
                    scanner.feedLine(line)
                    rowCount++
                    if (rowCount <= PREVIEW_ROWS) {
                        preview.append(line).append('\n')
                    }
                }

                override fun doAfterAllAnalysed(context: AnalysisContext?) = Unit
            }

            EasyExcel.read(input)
                .registerReadListener(listener)
                .autoCloseStream(false)
                .headRowNumber(1)
                .sheet()
                .doRead()
        }

        if (rowCount == 0 && header.isEmpty()) return fail("表格为空，或不是可解析的 xlsx")
        val scanned = scanner.previewText().ifEmpty { preview.toString() }
        val result = finish(
            method = "Excel 流式读取（共 " + rowCount + " 行）",
            fullText = scanned,
            previewText = preview.toString().trimEnd(),
            previewKind = "table",
            scanner = scanner,
        )
        return result.copy(summary = ("表头：" + header.joinToString("、") + "；共 " + rowCount + " 行数据").take(160))
    }

    // ---------- PDF ----------

    private fun parsePdf(path: Path, fileSize: Long): ParsedFile {
        val extracted = PdfTextExtractor.extract(path)
        if (extracted.isEmpty()) return fail("PDF 未抽取到文本（扫描件/纯图片或结构异常），跳过")
        if (!PdfTextExtractor.looksTextBased(extracted)) {
            return fail("PDF 未抽取到文本（可能是扫描件/加密或纯图片 PDF），跳过")
        }
        if (!PdfTextExtractor.looksReliable(extracted, fileSize)) {
            return fail("PDF 文本层使用嵌入子集字体（未做逐字体重映射）或为扫描件，只抽到少量字形码，内容不可靠，已跳过判定")
        }
        val scanner = StreamingSensitiveScanner()
        var offset = 0
        while (offset < extracted.length) {
            val end = minOf(offset + CHUNK, extracted.length)
            scanner.feed(extracted.substring(offset, end))
            offset = end
        }
        return build("PDF 抽文本（分块扫描）", extracted, scanner, "text")
    }

    // ---------- 压缩包 ----------

    private fun parseZip(path: Path): ParsedFile {
        ZipFile(path.toFile()).use { zip ->
            val entries = ArrayList<java.util.zip.ZipEntry>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (!entry.isDirectory) entries.add(entry)
            }
            val picked = entries.take(MAX_ZIP_ENTRIES)
            if (picked.isEmpty()) return fail("压缩包内没有文件，跳过")

            val scanner = StreamingSensitiveScanner()
            var ocrCount = 0
            for (entry in picked) {
                val name = entry.name.substringAfterLast('/')
                val ext = name.substringAfterLast('.', "").lowercase()
                scanner.feedLine("[" + entry.name + "]")
                when {
                    ext in TEXT_EXTS -> zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(CHUNK)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            scanner.feed(String(buffer, 0, read))
                        }
                    }
                    ext in IMAGE_EXTS -> {
                        if (ocrCount < MAX_ZIP_OCR) {
                            ocrCount++
                            val bytes = zip.getInputStream(entry).readBytes()
                            val temp = Files.createTempFile("acd-zip-", "." + ext)
                            try {
                                Files.write(temp, bytes)
                                scanner.feedLine(ocr.recognize(temp) ?: "（OCR 无结果）")
                            } finally {
                                Files.deleteIfExists(temp)
                            }
                        } else {
                            scanner.feedLine("（图片未逐张识别）")
                        }
                    }
                    else -> scanner.feedLine("（其他格式，未解析）")
                }
            }
            val preview = scanner.previewText().trimEnd()
            return finish("受控解包（" + picked.size + " 个文件）", preview, preview, "text", scanner)
        }
    }

    // ---------- 图片 ----------

    private fun parseImage(path: Path): ParsedFile {
        val text = ocr.recognize(path)
        if (text.isNullOrBlank()) return fail("图片未识别出文字（OCR 无结果或引擎不可用），不参与内容判定")
        val scanner = StreamingSensitiveScanner()
        scanner.feed(text)
        return build("图片 OCR", text, scanner, "text")
    }

    // ---------- 组装 ----------

    private fun build(method: String, fullText: String, scanner: StreamingSensitiveScanner, previewKind: String): ParsedFile =
        finish(method, fullText, scanner.previewText(), previewKind, scanner)

    private fun finish(
        method: String,
        fullText: String,
        previewText: String,
        previewKind: String,
        scanner: StreamingSensitiveScanner,
    ): ParsedFile {
        val clean = fullText.replace(Regex("[ \t]+"), " ").replace(Regex("""\n{3,}"""), "\n\n").trim()
        val outcome = scanner.result()
        val summary = clean.replace(Regex("""\s+"""), " ").take(160).ifEmpty { "（内容为空）" }
        return ParsedFile(
            ok = true,
            method = method,
            text = clean.take(MAX_TEXT),
            summary = summary,
            risk = outcome.risk,
            copied = outcome.copied,
            copiedMarkers = outcome.copiedMarkers,
            previewText = previewText.take(MAX_PREVIEW),
            previewKind = previewKind,
            truncated = scanner.truncated || clean.length > MAX_TEXT,
        )
    }

    private fun fail(note: String) = ParsedFile(ok = false, method = "未解析", text = "", summary = "", note = note)

    companion object {
        private const val MAX_SIZE = 20L * 1024 * 1024
        private const val MAX_TEXT = 200_000
        private const val MAX_PREVIEW = 6000
        private const val PREVIEW_ROWS = 12
        private const val MAX_ZIP_ENTRIES = 20
        private const val MAX_ZIP_OCR = 3
        private const val CHUNK = 8192
        private val TEXT_EXTS = setOf(
            "txt", "md", "json", "xml", "yml", "yaml", "csv", "log", "ini", "conf",
            "kt", "java", "js", "ts", "html", "css", "sql", "sh", "bat", "py",
        )
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    }
}
