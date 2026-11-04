package com.pgigi.agentclouddisk.agent

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Inflater

/** 极简 PDF 文本抽取：处理未压缩与 FlateDecode 的内容流，够 Demo 的常规文本型 PDF 使用 */
object PdfTextExtractor {

    fun extract(path: Path, maxBytes: Int = 8 * 1024 * 1024): String {
        val bytes = Files.readAllBytes(path)
        if (bytes.size > maxBytes) return ""
        val raw = String(bytes, Charsets.ISO_8859_1)
        val builder = StringBuilder()
        val streamRegex = Regex("stream\\r?\\n(.*?)endstream", RegexOption.DOT_MATCHES_ALL)
        for (match in streamRegex.findAll(raw)) {
            val header = raw.substring(maxOf(0, match.range.first - 200), match.range.first)
            val content = match.groupValues[1]
            val text = if (header.contains("FlateDecode")) inflate(content) else content
            if (text.isNotEmpty()) builder.append(extractTextOperators(text)).append('\n')
        }
        return builder.toString().replace(Regex("[ \t]+"), " ").trim()
    }

    private fun inflate(content: String): String = runCatching {
        val compressed = content.toByteArray(Charsets.ISO_8859_1)
        val inflater = Inflater()
        inflater.setInput(compressed)
        val buffer = ByteArray(compressed.size * 6 + 1024)
        val length = inflater.inflate(buffer)
        inflater.end()
        String(buffer, 0, length, Charsets.ISO_8859_1)
    }.getOrElse { "" }

    private fun extractTextOperators(content: String): String {
        val builder = StringBuilder()
        val literal = Regex("\\((?:[^()\\\\]|\\\\.)*\\)")
        for (match in literal.findAll(content)) {
            builder.append(unescape(match.value.substring(1, match.value.length - 1))).append(' ')
        }
        if (builder.isEmpty()) return ""
        val lines = content.split("Td", "TD", "T*", "ET")
        return if (lines.size > 1) builder.toString() else builder.toString()
    }

    private fun unescape(text: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                when (val next = text[index + 1]) {
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    '(', ')', '\\' -> builder.append(next)
                    in '0'..'7' -> {
                        var octal = ""
                        var cursor = index + 1
                        while (cursor < text.length && octal.length < 3 && text[cursor] in '0'..'7') {
                            octal += text[cursor]
                            cursor++
                        }
                        octal.toIntOrNull(8)?.let { builder.append(it.toChar()) }
                        index = cursor - 1
                    }
                    else -> builder.append(next)
                }
                index += 2
            } else {
                builder.append(char)
                index++
            }
        }
        return builder.toString()
    }

    /** 判断 PDF 是否可解析出文本（供解析器给出如实说明） */
    fun looksTextBased(text: String): Boolean = text.length >= 8 && text.any { it.isLetterOrDigit() }
}
