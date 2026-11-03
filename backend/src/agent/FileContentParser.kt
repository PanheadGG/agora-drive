package com.pgigi.agentclouddisk.agent

import com.pgigi.agentclouddisk.service.StorageService
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 真实内容解析：只用文件本身的内容判定，不看文件名。
 * Demo 支持 文本类 / docx / xlsx；图片 OCR、PDF、压缩包受环境限制，如实标记为未解析。
 */
@Service
class FileContentParser(private val storage: StorageService) {

    data class ParsedFile(
        val ok: Boolean,
        val method: String,
        val text: String,
        val summary: String,
        val note: String? = null,
    )

    fun parse(sha256: String?, fileName: String, size: Long): ParsedFile {
        if (sha256.isNullOrBlank()) return fail("文件元数据缺失，无法定位存储内容")
        val path = storage.contentPath(sha256)
        if (!Files.exists(path)) return fail("存储中找不到文件内容，可能已被清理")
        if (size > MAX_SIZE) return fail("文件超过 Demo 解析上限（10MB），跳过")

        val ext = fileName.substringAfterLast('.', "").lowercase()
        return runCatching {
            when (ext) {
                in TEXT_EXTS -> parseText(path)
                "docx" -> parseDocx(path)
                "xlsx" -> parseXlsx(path)
                in IMAGE_EXTS -> fail("图片需 OCR/视觉识别，服务端 Demo 未内置识别引擎，仅登记元信息，不参与内容判定")
                "pdf" -> fail("PDF 文本抽取未内置（Demo 限制），跳过")
                "zip", "rar", "7z" -> fail("压缩包不做受控解包（Demo 限制），跳过")
                else -> fail("暂不支持解析该格式（.$ext）")
            }
        }.getOrElse { fail("解析异常：" + (it.message ?: it.javaClass.simpleName)) }
    }

    private fun parseText(path: Path): ParsedFile {
        val bytes = Files.readAllBytes(path)
        val text = String(bytes, Charsets.UTF_8).replace("\u0000", "")
        return ok("抽正文", text)
    }

    private fun parseDocx(path: Path): ParsedFile {
        ZipFile(path.toFile()).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return fail("docx 结构不完整，缺少正文")
            val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            val text = xml
                .replace(Regex("</w:p>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
            return ok("抽正文", text)
        }
    }

    private fun parseXlsx(path: Path): ParsedFile {
        ZipFile(path.toFile()).use { zip ->
            val shared = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                val xml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
                    .map { it.groupValues[1].replace(Regex("<[^>]+>"), "") }
                    .toList()
            } ?: emptyList()

            val sheet = zip.getEntry("xl/worksheets/sheet1.xml") ?: return fail("xlsx 缺少工作表")
            val xml = zip.getInputStream(sheet).readBytes().toString(Charsets.UTF_8)
            val rows = xml.split("</row>").filter { it.contains("<c") }
            val builder = StringBuilder()
            for (row in rows) {
                val cells = Regex("<c([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL).findAll(row).map { cell ->
                    val attrs = cell.groupValues[1]
                    val inner = cell.groupValues[2]
                    val value = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1) ?: ""
                    if (attrs.contains("t=\"s\"")) shared.getOrNull(value.toIntOrNull() ?: -1) ?: "" else value
                }
                builder.append(cells.joinToString(" | ")).append("\n")
            }
            return ok("读表头与单元格", builder.toString())
        }
    }

    private fun ok(method: String, rawText: String): ParsedFile {
        val text = rawText.replace(Regex("[ \t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
        val summary = text.replace(Regex("""\s+"""), " ").take(160)
        return ParsedFile(true, method, text, summary.ifEmpty { "（内容为空）" })
    }

    private fun fail(note: String) = ParsedFile(false, "未解析", "", "", note)

    companion object {
        private const val MAX_SIZE = 10L * 1024 * 1024
        private val TEXT_EXTS = setOf(
            "txt", "md", "json", "xml", "yml", "yaml", "csv", "log", "ini", "conf",
            "kt", "java", "js", "ts", "html", "css", "sql", "sh", "bat", "py",
        )
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic")
    }
}
