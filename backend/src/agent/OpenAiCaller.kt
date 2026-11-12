package com.pgigi.agentclouddisk.agent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** OpenAI 兼容协议的最小调用封装（DeepSeek / 通义 / 智谱 / 自建网关都走这里） */
@Component
class OpenAiCaller {

    private val log = LoggerFactory.getLogger(OpenAiCaller::class.java)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    data class Result(
        val ok: Boolean,
        val statusCode: Int,
        val content: String?,
        val latencyMs: Long,
        val error: String? = null,
    )

    /** 把本地图片编码成 data URL，供多模态模型识图 */
    fun imageDataUrl(path: java.nio.file.Path): String? = runCatching {
        val bytes = java.nio.file.Files.readAllBytes(path)
        if (bytes.size > MAX_IMAGE_BYTES) return null
        val ext = path.fileName.toString().substringAfterLast('.', "png").lowercase()
        val mime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
        "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }.getOrNull()

    /** 多模态对话：messages 由调用方组装（可含 image_url 内容块） */
    fun chatRaw(baseUrl: String, apiKey: String, payload: String, timeoutSeconds: Long): Result {
        val startedAt = System.currentTimeMillis()
        val endpoint = baseUrl.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else it + "/chat/completions" }
        return try {
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            val latency = System.currentTimeMillis() - startedAt
            if (response.statusCode() !in 200..299) {
                Result(false, response.statusCode(), null, latency, "HTTP " + response.statusCode() + " " + response.body().take(200))
            } else {
                val body = Json.parseObject(response.body())
                val choices = Json.asList(body?.get("choices"))
                val message = Json.asMap(Json.asMap(choices?.firstOrNull())?.get("message"))
                val content = Json.asString(message?.get("content"))
                if (content.isNullOrBlank()) Result(false, response.statusCode(), null, latency, "empty completion")
                else Result(true, response.statusCode(), content, latency, null)
            }
        } catch (error: Exception) {
            val latency = System.currentTimeMillis() - startedAt
            log.warn("vision call failed endpoint={} error={}", endpoint, error.message)
            Result(false, 0, null, latency, error.message ?: error.javaClass.simpleName)
        }
    }

    fun chat(baseUrl: String, apiKey: String, model: String, payload: String, timeoutSeconds: Long): Result {
        val startedAt = System.currentTimeMillis()
        val endpoint = baseUrl.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else it + "/chat/completions" }
        return try {
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
                .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            val latency = System.currentTimeMillis() - startedAt
            if (response.statusCode() !in 200..299) {
                Result(false, response.statusCode(), null, latency, "HTTP " + response.statusCode() + " " + response.body().take(200))
            } else {
                val body = Json.parseObject(response.body())
                val choices = Json.asList(body?.get("choices"))
                val message = Json.asMap(Json.asMap(choices?.firstOrNull())?.get("message"))
                val content = Json.asString(message?.get("content"))
                if (content.isNullOrBlank()) {
                    Result(false, response.statusCode(), null, latency, "empty completion")
                } else {
                    Result(true, response.statusCode(), content, latency, null)
                }
            }
        } catch (error: Exception) {
            val latency = System.currentTimeMillis() - startedAt
            log.warn("llm call failed endpoint={} error={}", endpoint, error.message)
            Result(false, 0, null, latency, error.message ?: error.javaClass.simpleName)
        }
    }

    private companion object {
        /** 单张图上限，避免请求体过大 */
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    }

    /** 把模型返回的内容解析成 JSON 对象（兼容代码块包裹） */
    fun parseJsonContent(content: String): Map<String, Any?>? {
        val json = Json.extractJsonObject(content) ?: return null
        return Json.parseObject(json)
    }
}
