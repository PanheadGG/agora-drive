package com.pgigi.agentclouddisk.agent

/** 极简 JSON 读写（后端不引入 Jackson，用于 LLM 请求/响应） */
object Json {

    fun parse(text: String): Any? = Parser(text).run {
        val value = readValue()
        value
    }

    fun parseObject(text: String): Map<String, Any?>? = parse(text) as? Map<String, Any?>

    fun stringify(value: Any?): String = buildString { write(value) }

    fun asMap(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    fun asList(value: Any?): List<Any?>? = value as? List<Any?>

    fun asString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
        is Boolean -> value.toString()
        else -> stringify(value)
    }

    fun asInt(value: Any?, default: Int = 0): Int = when (value) {
        is Double -> value.toInt()
        is Int -> value
        is Long -> value.toInt()
        is String -> value.trim().toIntOrNull() ?: default
        else -> default
    }

    fun asStringList(value: Any?): List<String> = asList(value)?.mapNotNull { asString(it) } ?: emptyList()

    /** 兼容模型把 JSON 包在代码块里、或前后带解释文字的情况 */
    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (position in start until text.length) {
            val char = text[position]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, position + 1)
                }
            }
        }
        return null
    }

    private fun StringBuilder.write(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> append('"').append(escape(value)).append('"')
            is Boolean -> append(value.toString())
            is Int, is Long -> append(value.toString())
            is Double -> append(if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString())
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) append(',')
                    first = false
                    append('"').append(escape(key.toString())).append("\":")
                    write(item)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                var first = true
                for (item in value) {
                    if (!first) append(',')
                    first = false
                    write(item)
                }
                append(']')
            }
            else -> append('"').append(escape(value.toString())).append('"')
        }
    }

    fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private class Parser(private val text: String) {
        private var index = 0

        fun readValue(): Any? {
            skipWhitespace()
            if (index >= text.length) return null
            return when (text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral(4, true)
                'f' -> readLiteral(5, false)
                'n' -> readLiteral(4, null)
                else -> readNumber()
            }
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            index++
            skipWhitespace()
            if (index < text.length && text[index] == '}') {
                index++
                return result
            }
            while (index < text.length) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                if (index < text.length && text[index] == ':') index++
                result[key] = readValue()
                skipWhitespace()
                if (index < text.length && text[index] == ',') {
                    index++
                    continue
                }
                if (index < text.length && text[index] == '}') {
                    index++
                    break
                }
                break
            }
            return result
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            index++
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return result
            }
            while (index < text.length) {
                result.add(readValue())
                skipWhitespace()
                if (index < text.length && text[index] == ',') {
                    index++
                    continue
                }
                if (index < text.length && text[index] == ']') {
                    index++
                    break
                }
                break
            }
            return result
        }

        private fun readString(): String {
            val builder = StringBuilder()
            if (index < text.length && text[index] == '"') index++
            while (index < text.length) {
                val char = text[index++]
                when (char) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (index >= text.length) return builder.toString()
                        when (val escaped = text[index++]) {
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'u' -> {
                                val hex = text.substring(index, minOf(index + 4, text.length))
                                index += hex.length
                                builder.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            }
                            else -> builder.append(escaped)
                        }
                    }
                    else -> builder.append(char)
                }
            }
            return builder.toString()
        }

        private fun readNumber(): Any? {
            val start = index
            if (index < text.length && (text[index] == '-' || text[index] == '+')) index++
            while (index < text.length && (text[index].isDigit() || text[index] == '.' || text[index] == 'e' || text[index] == 'E' || text[index] == '-' || text[index] == '+')) index++
            return text.substring(start, index).toDoubleOrNull()
        }

        private fun readLiteral(length: Int, value: Any?): Any? {
            index += length
            return value
        }
    }
}
