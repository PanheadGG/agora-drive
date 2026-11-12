package com.pgigi.agentclouddisk.agent

import com.pgigi.agentclouddisk.model.LlmChainItem
import com.pgigi.agentclouddisk.model.LlmStatusDto
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 大模型统一入口：按管理员配置的优先级依次尝试各平台，
 * 某个平台失败（超时/4xx/5xx/网络不可达）立即切换到下一个，并记录健康状态与冷却时间。
 */
@Component
class LlmClient(
    private val providerService: AiProviderService,
    private val caller: OpenAiCaller,
    environment: Environment,
) {

    private val log = LoggerFactory.getLogger(LlmClient::class.java)

    private val enabled: Boolean = environment.getProperty("app.ai.enabled", Boolean::class.java, true) ?: true
    private val cooldownSeconds: Long = environment.getProperty("app.ai.cooldown-seconds", Long::class.java, 60L) ?: 60L

    private val calls = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val failoverCount = AtomicLong(0)

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastLatencyMs: Long = 0

    @Volatile
    private var lastProviderName: String? = null

    @Volatile
    private var activeProviderId: Long? = null

    private val cooldownUntil = ConcurrentHashMap<Long, Long>()

    val available: Boolean get() = enabled && providerService.chain().isNotEmpty()

    val activeModel: String get() = providerService.chain().firstOrNull()?.model ?: ""

    fun status(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "available" to available,
        "providerCount" to providerService.chain().size,
        "model" to activeModel,
        "calls" to calls.get(),
        "failures" to failures.get(),
        "failoverCount" to failoverCount.get(),
        "lastLatencyMs" to lastLatencyMs,
        "lastError" to lastError,
        "lastProvider" to lastProviderName,
        "crypto" to providerService.cryptoStatus(),
    )

    fun statusDto(): LlmStatusDto {
        val health = providerService.healthById()
        val chain = providerService.chain().map { candidate ->
            val (lastStatus, lastError) = health[candidate.id] ?: (null to null)
            LlmChainItem(
                id = candidate.id,
                name = candidate.name,
                provider = candidate.provider,
                model = candidate.model,
                priority = candidate.priority,
                healthy = !isCoolingDown(candidate.id),
                cooldownSeconds = coolingDownSeconds(candidate.id),
                lastStatus = lastStatus,
                lastError = lastError,
            )
        }
        return LlmStatusDto(
            available = enabled && chain.isNotEmpty(),
            providerCount = chain.size,
            activeProviderId = activeProviderId ?: chain.firstOrNull()?.id,
            activeProviderName = lastProviderName ?: chain.firstOrNull()?.name,
            activeModel = chain.firstOrNull()?.model,
            calls = calls.get(),
            failures = failures.get(),
            lastLatencyMs = lastLatencyMs,
            lastError = lastError,
            failoverCount = failoverCount.get(),
            chain = chain,
            crypto = providerService.cryptoStatus(),
        )
    }

    /** 依次尝试各平台，任何一家成功即返回；全部失败返回 null 由调用方降级为本地规则 */
    fun chatJson(systemPrompt: String, userPrompt: String, temperature: Double = 0.2): Map<String, Any?>? {
        if (!enabled) return null
        val candidates = providerService.chain()
        if (candidates.isEmpty()) return null

        val healthy = candidates.filterNot { isCoolingDown(it.id) }
        val cooling = candidates.filter { isCoolingDown(it.id) }
        val ordered = if (healthy.isNotEmpty()) healthy + cooling else candidates

        val payloadBody = linkedMapOf(
            "messages" to listOf(
                linkedMapOf("role" to "system", "content" to systemPrompt),
                linkedMapOf("role" to "user", "content" to userPrompt),
            ),
            "temperature" to temperature,
            "response_format" to linkedMapOf("type" to "json_object"),
            "stream" to false,
        )

        var attempted = 0
        for (candidate in ordered) {
            attempted++
            val payload = Json.stringify(linkedMapOf("model" to candidate.model) + payloadBody)
            val result = caller.chat(candidate.baseUrl, candidate.apiKey, candidate.model, payload, candidate.timeoutSeconds.toLong())
            calls.incrementAndGet()
            lastLatencyMs = result.latencyMs

            if (result.ok) {
                val parsed = caller.parseJsonContent(result.content ?: "")
                if (parsed == null) {
                    failures.incrementAndGet()
                    lastError = candidate.name + " 返回内容不是 JSON"
                    providerService.recordFailure(candidate.id, result.latencyMs, "response is not JSON")
                    markCooldown(candidate.id)
                    continue
                }
                providerService.recordSuccess(candidate.id, result.latencyMs)
                clearCooldown(candidate.id)
                lastProviderName = candidate.name
                activeProviderId = candidate.id
                if (attempted > 1) {
                    failoverCount.incrementAndGet()
                    log.warn("llm failover: 已自动切换到 {}（第 {} 个候选）", candidate.name, attempted)
                }
                return parsed
            }

            failures.incrementAndGet()
            lastError = candidate.name + ": " + (result.error ?: "调用失败")
            providerService.recordFailure(candidate.id, result.latencyMs, result.error)
            markCooldown(candidate.id)
            log.warn("llm provider {} 调用失败，尝试下一个：{}", candidate.name, result.error)
        }
        return null
    }

    /**
     * 多模态判定：把图片本体 + 已 OCR 出的文字一起交给视觉模型，
     * 用来识破"OCR 出了文字、但画面其实无关"（如白底截图、表情包、空白模板）。
     * 返回 null 表示没有可用平台或全部失败，调用方需降级。
     */
    fun chatVisionJson(
        systemPrompt: String,
        userPrompt: String,
        imagePath: java.nio.file.Path,
        temperature: Double = 0.1,
    ): Map<String, Any?>? {
        if (!enabled) return null
        val dataUrl = caller.imageDataUrl(imagePath) ?: return null
        val candidates = providerService.chain()
        if (candidates.isEmpty()) return null

        val healthy = candidates.filterNot { isCoolingDown(it.id) }
        val cooling = candidates.filter { isCoolingDown(it.id) }
        val ordered = if (healthy.isNotEmpty()) healthy + cooling else candidates

        val userContent = listOf(
            linkedMapOf("type" to "text", "text" to userPrompt),
            linkedMapOf("type" to "image_url", "image_url" to linkedMapOf("url" to dataUrl)),
        )

        var attempted = 0
        for (candidate in ordered) {
            attempted++
            val payload = Json.stringify(
                linkedMapOf(
                    "model" to candidate.model,
                    "messages" to listOf(
                        linkedMapOf("role" to "system", "content" to systemPrompt),
                        linkedMapOf("role" to "user", "content" to userContent),
                    ),
                    "temperature" to temperature,
                    "response_format" to linkedMapOf("type" to "json_object"),
                    "stream" to false,
                )
            )
            val result = caller.chatRaw(candidate.baseUrl, candidate.apiKey, payload, candidate.timeoutSeconds.toLong())
            calls.incrementAndGet()
            lastLatencyMs = result.latencyMs

            if (result.ok) {
                val parsed = caller.parseJsonContent(result.content ?: "")
                if (parsed == null) {
                    failures.incrementAndGet()
                    providerService.recordFailure(candidate.id, result.latencyMs, "vision response is not JSON")
                    markCooldown(candidate.id)
                    continue
                }
                providerService.recordSuccess(candidate.id, result.latencyMs)
                clearCooldown(candidate.id)
                lastProviderName = candidate.name
                activeProviderId = candidate.id
                if (attempted > 1) failoverCount.incrementAndGet()
                return parsed
            }

            failures.incrementAndGet()
            lastError = candidate.name + "（识图）: " + (result.error ?: "调用失败")
            providerService.recordFailure(candidate.id, result.latencyMs, result.error)
            markCooldown(candidate.id)
            log.warn("vision provider {} failed: {}", candidate.name, result.error)
        }
        return null
    }

    private fun isCoolingDown(id: Long): Boolean {
        val until = cooldownUntil[id] ?: return false
        if (until <= System.currentTimeMillis()) {
            cooldownUntil.remove(id)
            return false
        }
        return true
    }

    private fun coolingDownSeconds(id: Long): Long {
        val until = cooldownUntil[id] ?: return 0
        return ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    }

    private fun markCooldown(id: Long) {
        cooldownUntil[id] = System.currentTimeMillis() + cooldownSeconds * 1000
    }

    private fun clearCooldown(id: Long) {
        cooldownUntil.remove(id)
    }
}
