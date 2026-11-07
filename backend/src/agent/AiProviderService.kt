package com.pgigi.agentclouddisk.agent

import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.crypto.AesGcmCrypto
import com.pgigi.agentclouddisk.mapper.AiProviderMapper
import com.pgigi.agentclouddisk.model.AiProvider
import com.pgigi.agentclouddisk.model.AiProviderDto
import com.pgigi.agentclouddisk.model.AiProviderRequest
import com.pgigi.agentclouddisk.model.AiProviderTestResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 多平台 AI 模型管理：管理员在此维护任意 OpenAI 兼容平台，
 * API Key 用 AES-256-GCM 加密后入库，读取时按需解密，接口只返回脱敏串。
 */
@Service
class AiProviderService(
    private val mapper: AiProviderMapper,
    private val crypto: AesGcmCrypto,
    private val caller: OpenAiCaller,
) {

    private val log = LoggerFactory.getLogger(AiProviderService::class.java)

    /** 运行期候选：含解密后的明文 Key，只在内存里活一次 */
    data class Candidate(
        val id: Long,
        val name: String,
        val provider: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val priority: Int,
        val timeoutSeconds: Int,
    )

    fun list(): List<AiProviderDto> {
        val rows = mapper.listAll()
        val activeId = chain().firstOrNull()?.id
        return rows.map { it.toDto(activeId) }
    }

    fun chain(): List<Candidate> = mapper.listEnabled().mapNotNull { row ->
        runCatching {
            Candidate(
                id = row.id ?: 0,
                name = row.name,
                provider = row.provider,
                baseUrl = row.baseUrl,
                model = row.model,
                apiKey = crypto.decrypt(row.encryptedKey, row.iv, row.gcmTag, row.encryptAlgo),
                priority = row.priority,
                timeoutSeconds = row.timeoutSeconds,
            )
        }.getOrElse {
            log.warn("provider {} 解密失败（主密钥可能已更换）: {}", row.name, it.message)
            null
        }
    }

    fun create(req: AiProviderRequest): AiProviderDto {
        validate(req, requireKey = true)
        val encrypted = crypto.encrypt(req.apiKey.trim())
        val row = AiProvider(
            provider = req.provider.trim().lowercase(),
            name = req.name.trim().ifEmpty { req.provider.trim() },
            baseUrl = req.baseUrl.trim().trimEnd('/'),
            model = req.model.trim(),
            encryptedKey = encrypted.cipherText,
            iv = encrypted.iv,
            gcmTag = encrypted.tag,
            encryptAlgo = encrypted.algo,
            priority = req.priority,
            status = req.status,
            timeoutSeconds = req.timeoutSeconds,
        )
        val id = mapper.insert(row)
        log.info("ai provider created: id={} name={} model={}", id, row.name, row.model)
        return dto(id)
    }

    fun update(id: Long, req: AiProviderRequest): AiProviderDto {
        val existing = mapper.findById(id) ?: throw ApiException(404, "该模型不存在")
        validate(req, requireKey = false)
        existing.provider = req.provider.trim().lowercase()
        existing.name = req.name.trim().ifEmpty { existing.provider }
        existing.baseUrl = req.baseUrl.trim().trimEnd('/')
        existing.model = req.model.trim()
        existing.priority = req.priority
        existing.status = req.status
        existing.timeoutSeconds = req.timeoutSeconds
        mapper.updateMeta(existing)
        if (req.apiKey.isNotBlank()) {
            val encrypted = crypto.encrypt(req.apiKey.trim())
            existing.encryptedKey = encrypted.cipherText
            existing.iv = encrypted.iv
            existing.gcmTag = encrypted.tag
            existing.encryptAlgo = encrypted.algo
            mapper.updateKey(existing)
            log.info("ai provider {} api key rotated", id)
        }
        return dto(id)
    }

    fun delete(id: Long) {
        mapper.findById(id) ?: throw ApiException(404, "该模型不存在")
        mapper.delete(id)
        log.info("ai provider {} deleted", id)
    }

    fun setStatus(id: Long, status: Int): AiProviderDto {
        if (status != 0 && status != 1) throw ApiException(400, "状态值非法")
        mapper.findById(id) ?: throw ApiException(404, "该模型不存在")
        mapper.updateStatus(id, status)
        return dto(id)
    }

    fun setPriority(id: Long, priority: Int): AiProviderDto {
        if (priority < 0 || priority > 9999) throw ApiException(400, "优先级需在 0-9999 之间")
        mapper.findById(id) ?: throw ApiException(404, "该模型不存在")
        mapper.updatePriority(id, priority)
        return dto(id)
    }

    /** 真实连通性测试：发一条最小对话，成功即记录健康状态 */
    fun test(id: Long): AiProviderTestResult {
        val row = mapper.findById(id) ?: throw ApiException(404, "该模型不存在")
        val apiKey = crypto.decrypt(row.encryptedKey, row.iv, row.gcmTag, row.encryptAlgo)
        val payload = Json.stringify(
            linkedMapOf(
                "model" to row.model,
                "messages" to listOf(
                    linkedMapOf("role" to "system", "content" to "只输出单个 JSON 对象"),
                    linkedMapOf("role" to "user", "content" to """请输出 {"ok":true}"""),
                ),
                "temperature" to 0.0,
                "response_format" to linkedMapOf("type" to "json_object"),
                "stream" to false,
            )
        )
        val result = caller.chat(row.baseUrl, apiKey, row.model, payload, row.timeoutSeconds.toLong())
        mapper.recordHealth(
            id,
            if (result.ok) "ok" else "failed",
            result.error?.take(500),
            result.latencyMs,
            if (result.ok) 1 else 0,
            if (result.ok) 0 else 1,
        )
        return AiProviderTestResult(
            id = id,
            ok = result.ok,
            latencyMs = result.latencyMs,
            message = if (result.ok) "连接正常" else ("连接失败：" + (result.error ?: "未知错误")),
            sample = result.content?.take(120),
        )
    }

    fun recordSuccess(id: Long, latencyMs: Long) =
        mapper.recordHealth(id, "ok", null, latencyMs, 1, 0)

    fun recordFailure(id: Long, latencyMs: Long, error: String?) =
        mapper.recordHealth(id, "failed", error?.take(500), latencyMs, 0, 1)

    fun cryptoStatus(): Map<String, Any?> = crypto.status()

    /** id -> (lastStatus, lastError)，供链路状态展示 */
    fun healthById(): Map<Long, Pair<String?, String?>> =
        mapper.listAll().associate { (it.id ?: 0) to (it.lastStatus to it.lastError) }

    private fun dto(id: Long): AiProviderDto {
        val row = mapper.findById(id) ?: throw ApiException(404, "该模型不存在")
        return row.toDto(chain().firstOrNull()?.id)
    }

    private fun AiProvider.toDto(activeId: Long?): AiProviderDto {
        val masked = runCatching {
            crypto.mask(crypto.decrypt(encryptedKey, iv, gcmTag, encryptAlgo))
        }.getOrElse { "解密失败" }
        return AiProviderDto(
            id = id ?: 0,
            provider = provider,
            name = name,
            baseUrl = baseUrl,
            model = model,
            maskedKey = masked,
            encryptAlgo = encryptAlgo,
            priority = priority,
            status = status,
            statusLabel = if (status == 1) "启用" else "停用",
            timeoutSeconds = timeoutSeconds,
            lastStatus = lastStatus,
            lastError = lastError,
            lastCheckedAt = lastCheckedAt,
            lastLatencyMs = lastLatencyMs,
            successCount = successCount,
            failureCount = failureCount,
            createdAt = createdAt,
            active = (id ?: 0) == activeId,
        )
    }

    private fun validate(req: AiProviderRequest, requireKey: Boolean) {
        if (req.provider.isBlank()) throw ApiException(400, "平台标识不能为空（如 deepseek / openai / qwen）")
        if (req.name.isBlank()) throw ApiException(400, "名称不能为空")
        val url = req.baseUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw ApiException(400, "Base URL 必须以 http:// 或 https:// 开头")
        if (req.model.isBlank()) throw ApiException(400, "模型名不能为空")
        if (requireKey && req.apiKey.isBlank()) throw ApiException(400, "API Key 不能为空")
        if (req.timeoutSeconds !in 5..600) throw ApiException(400, "超时时间需在 5-600 秒之间")
        if (req.status != 0 && req.status != 1) throw ApiException(400, "状态值非法")
    }
}
