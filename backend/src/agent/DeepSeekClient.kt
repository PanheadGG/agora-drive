package com.pgigi.agentclouddisk.agent

import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * DeepSeek 对话补全客户端（OpenAI 兼容协议）。
 * 未配置 API Key 时自动降级为本地规则，功能仍可跑通。
 */
@Component
class DeepSeekClient(environment: Environment) {

    private val log = LoggerFactory.getLogger(DeepSeekClient::class.java)

    private val enabled: Boolean = environment.getProperty("app.deepseek.enabled", Boolean::class.java, true) ?: true
    private val apiKey: String = environment.getProperty("app.deepseek.api-key", "") ?: ""
    private val baseUrl: String = environment.getProperty("app.deepseek.base-url", "https://api.deepseek.com") ?: "https://api.deepseek.com"
    private val model: String = environment.getProperty("app.deepseek.model", "deepseek-chat") ?: "deepseek-chat"
    private val timeoutSeconds: Long = environment.getProperty("app.deepseek.timeout-seconds", Long::class.java, 60L) ?: 60L

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val calls = AtomicInteger(0)
    private val failures = AtomicInteger(0)

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastLatencyMs: Long = 0

    val available: Boolean get() = enabled && apiKey.isNotBlank()

    val activeModel: String get() = model

    fun status(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "available" to available,
        "configured" to apiKey.isNotBlank(),
        "model" to model,
        "baseUrl" to baseUrl,
        "calls" to calls.get(),
        "failures" to failures.get(),
        "lastLatencyMs" to lastLatencyMs,
        "lastError" to lastError,
    )

    /** 调用一次 JSON 模式对话，返回解析后的对象；任何异常都返回 null 由调用方降级 */
    fun chatJson(systemPrompt: String, userPrompt: String, temperature: Double = 0.2): Map<String, Any?>? {
        if (!available) return null
        val payload = Json.stringify(
            linkedMapOf(
                "model" to model,
                "messages" to listOf(
                    linkedMapOf("role" to "system", "content" to systemPrompt),
                    linkedMapOf("role" to "user", "content" to userPrompt),
                ),
                "temperature" to temperature,
                "response_format" to linkedMapOf("type" to "json_object"),
                "stream" to false,
            )
        )

        val startedAt = System.currentTimeMillis()
        return try {
            val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
                .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            lastLatencyMs = System.currentTimeMillis() - startedAt
            calls.incrementAndGet()

            if (response.statusCode() !in 200..299) {
                failures.incrementAndGet()
                lastError = "HTTP " + response.statusCode() + " " + response.body().take(200)
                log.warn("deepseek call failed: {}", lastError)
                return null
            }

            val body = Json.parseObject(response.body())
            val choices = Json.asList(body?.get("choices"))
            val message = Json.asMap(Json.asMap(choices?.firstOrNull())?.get("message"))
            val content = Json.asString(message?.get("content"))
            if (content.isNullOrBlank()) {
                failures.incrementAndGet()
                lastError = "empty completion"
                return null
            }
            val json = Json.extractJsonObject(content)
            if (json == null) {
                failures.incrementAndGet()
                lastError = "response is not JSON: " + content.take(120)
                return null
            }
            Json.parseObject(json)
        } catch (error: Exception) {
            failures.incrementAndGet()
            lastLatencyMs = System.currentTimeMillis() - startedAt
            lastError = error.message ?: error.javaClass.simpleName
            log.warn("deepseek call error: {}", lastError)
            null
        }
    }
}
