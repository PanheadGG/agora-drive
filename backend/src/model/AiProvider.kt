package com.pgigi.agentclouddisk.model

/** ai_provider 表实体：Key 只以密文 + IV + tag 形式存在 */
data class AiProvider(
    var id: Long? = null,
    var provider: String = "",
    var name: String = "",
    var baseUrl: String = "",
    var model: String = "",
    var encryptedKey: ByteArray = ByteArray(0),
    var iv: ByteArray = ByteArray(0),
    var gcmTag: ByteArray = ByteArray(0),
    var encryptAlgo: String = "aes-gcm-256",
    var priority: Int = 100,
    var status: Int = 1,
    var timeoutSeconds: Int = 60,
    var lastStatus: String? = null,
    var lastError: String? = null,
    var lastCheckedAt: String? = null,
    var lastLatencyMs: Long? = null,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var createdAt: String? = null,
    var updatedAt: String? = null,
)

/** 管理员看到的视图：Key 仅展示脱敏串，绝不返回明文 */
data class AiProviderDto(
    val id: Long,
    val provider: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val maskedKey: String,
    val encryptAlgo: String,
    val priority: Int,
    val status: Int,
    val statusLabel: String,
    val timeoutSeconds: Int,
    val lastStatus: String?,
    val lastError: String?,
    val lastCheckedAt: String?,
    val lastLatencyMs: Long?,
    val successCount: Int,
    val failureCount: Int,
    val createdAt: String?,
    val active: Boolean,
)

data class AiProviderRequest(
    val provider: String = "deepseek",
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val priority: Int = 100,
    val status: Int = 1,
    val timeoutSeconds: Int = 60,
)

data class AiProviderTestResult(
    val id: Long,
    val ok: Boolean,
    val latencyMs: Long,
    val message: String,
    val sample: String? = null,
)

data class LlmChainItem(
    val id: Long,
    val name: String,
    val provider: String,
    val model: String,
    val priority: Int,
    val healthy: Boolean,
    val cooldownSeconds: Long,
    val lastStatus: String?,
    val lastError: String?,
)

data class LlmStatusDto(
    val available: Boolean,
    val providerCount: Int,
    val activeProviderId: Long?,
    val activeProviderName: String?,
    val activeModel: String?,
    val calls: Long,
    val failures: Long,
    val lastLatencyMs: Long,
    val lastError: String?,
    val failoverCount: Long,
    val chain: List<LlmChainItem>,
    val crypto: Map<String, Any?>,
)
