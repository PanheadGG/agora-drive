package com.pgigi.agentclouddisk.agent

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Inflater

/**
 * 极简 PDF 文本抽取（无第三方依赖）。
 *
 * 关键点：PDF 里除了页面内容流，还有大量图片、嵌入字体、元数据等**二进制流**。
 * 之前用正则 `((?:[^()\\]|\\.)*)` 直接扫每个流，遇到几百 KB 的 JPEG 数据会触发
 * Java 正则的递归回溯，直接 StackOverflowError。
 * 现在改为：
 * 1. 按 indexOf 定位 stream / endstream，不用跨全文的惰性正则；
 * 2. 只处理"看起来像内容流"的流（含 BT 且含 Tj/TJ），图片/字体/元数据直接跳过；
 * 3. 用**手写循环**解析 PDF 字面量字符串，完全不用正则，不递归；
 * 4. 单流大小、总流数、输出长度都有上限，异常流直接跳过。
 */
object PdfTextExtractor {

    fun extract(path: Path, maxBytes: Int = MAX_BYTES): String {
        val bytes = Files.readAllBytes(path)
        if (bytes.size > maxBytes) return ""
        val raw = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder()
        var cursor = 0
        var streams = 0

        while (out.length < MAX_TEXT) {
            val marker = raw.indexOf("stream", cursor)
            if (marker < 0) break
            var start = marker + "stream".length
            if (start < raw.length && raw[start] == '\r') start++
            if (start < raw.length && raw[start] == '\n') start++
            val end = raw.indexOf("endstream", start)
            if (end < 0) break
            cursor = end + "endstream".length
            streams++
            if (streams > MAX_STREAMS) break

            val length = end - start
            if (length <= 0 || length > MAX_STREAM_CHARS) continue

            val dict = raw.substring(maxOf(0, marker - DICT_WINDOW), marker)
            if (isNonTextStream(dict)) continue

            val chunk = raw.substring(start, end)
            val decoded = if (dict.contains("FlateDecode")) inflate(chunk) else chunk
            if (decoded.isEmpty()) continue
            if (!looksLikeContentStream(decoded)) continue

            appendLiteralStrings(decoded, out)
        }
        return out.toString().replace(Regex("[ \t]+"), " ").trim()
    }

    fun looksTextBased(text: String): Boolean = text.length >= 8 && text.any { it.isLetterOrDigit() }

    /**
     * 判断抽取结果是否可信。PDF 常用"子集字体 + 自定义编码"，
     * 不做逐字体 ToUnicode 重映射时只能拿到字形码，抽出来是乱码。
     * 这里用"抽取量相对文件大小过小 + 完全没有中日韩字符"作为不可靠信号，避免拿乱码去判定。
     */
    fun looksReliable(text: String, fileSize: Long): Boolean {
        if (text.length < 40) return false
        val meaningful = text.count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,:;!?-_/()[]%*#@'\"" }
        if (meaningful.toDouble() / text.length < 0.8) return false
        // 大文件却只抽到一丁点文本、且完全没有中日韩字符 → 基本都是未映射的字形码
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        if (fileSize > 20_000 && cjk == 0 && text.length < fileSize / 40) return false
        return true
    }

    // ---------- 内部实现 ----------

    private fun isNonTextStream(dict: String): Boolean = NON_TEXT_MARKERS.any { dict.contains(it) }

    /** 内容流一般同时出现 BT（文本块开始）与 Tj/TJ（显示文字） */
    private fun looksLikeContentStream(text: String): Boolean =
        text.contains("BT") && (text.contains("Tj") || text.contains("TJ"))

    private fun inflate(content: String): String = runCatching {
        val compressed = content.toByteArray(Charsets.ISO_8859_1)
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val buffer = ByteArray(OUT_BUFFER)
            val output = ByteArrayOutputStream()
            while (!inflater.finished() && output.size() <= MAX_STREAM_BYTES) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    output.write(buffer, 0, read)
                }
            }
            String(output.toByteArray(), Charsets.ISO_8859_1)
        } finally {
            inflater.end()
        }
    }.getOrElse { "" }

    /** 手写解析 PDF 字面量字符串 (...) ，支持 \\ 转义与八进制，绝不递归 */
    private fun appendLiteralStrings(content: String, out: StringBuilder) {
        var index = 0
        val length = content.length
        while (index < length && out.length < MAX_TEXT) {
            if (content[index] != '(') {
                index++
                continue
            }
            index++
            var depth = 1
            val literal = StringBuilder()
            var ok = true
            while (index < length) {
                val char = content[index]
                if (char == '\\' && index + 1 < length) {
                    val next = content[index + 1]
                    var consumed = 2
                    when {
                        next == 'n' -> literal.append('\n')
                        next == 'r' -> literal.append('\r')
                        next == 't' -> literal.append('\t')
                        next == 'b' -> literal.append('\b')
                        next == 'f' -> literal.append('\u000C')
                        next == '(' || next == ')' || next == '\\' -> literal.append(next)
                        next == '\n' || next == '\r' -> Unit
                        next in '0'..'7' -> {
                            var octal = 0
                            var digits = 0
                            var probe = index + 1
                            while (probe < length && digits < 3 && content[probe] in '0'..'7') {
                                octal = octal * 8 + (content[probe] - '0')
                                probe++
                                digits++
                            }
                            literal.append(octal.toChar())
                            consumed = probe - index
                        }
                        else -> literal.append(next)
                    }
                    index += consumed
                    continue
                }
                if (char == '(') {
                    depth++
                    index++
                    continue
                }
                if (char == ')') {
                    depth--
                    index++
                    if (depth == 0) break
                    literal.append(')')
                    continue
                }
                literal.append(char)
                index++
                // 畸形数据里可能没有配对的右括号，限制长度避免吞掉整段
                if (literal.length > MAX_LITERAL) {
                    ok = false
                    break
                }
            }
            if (ok && literal.isNotEmpty()) {
                out.append(literal).append(' ')
            }
        }
    }

    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_STREAM_CHARS = 8 * 1024 * 1024
    private const val MAX_STREAM_BYTES = 4 * 1024 * 1024
    private const val MAX_STREAMS = 200
    private const val MAX_TEXT = 200_000
    private const val MAX_LITERAL = 20_000
    private const val DICT_WINDOW = 400
    private const val OUT_BUFFER = 16 * 1024

    /** 二进制流标记：图片、嵌入字体、元数据、对象流、交叉引用流 */
    private val NON_TEXT_MARKERS = listOf(
        "/Subtype/Image", "/Subtype /Image", "/FontFile", "/FontFile2", "/FontFile3",
        "/Length1", "/Metadata", "/ICCBased", "/EmbeddedFile", "/Type/ObjStm",
        "/Type/XRef", "/Type/ObjStm", "/ColorSpace",
    )
}
