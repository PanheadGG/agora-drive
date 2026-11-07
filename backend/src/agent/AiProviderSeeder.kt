package com.pgigi.agentclouddisk.agent

import com.pgigi.agentclouddisk.mapper.AiProviderMapper
import com.pgigi.agentclouddisk.model.AiProviderRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * 首次启动时把环境变量里的 DeepSeek Key 落成一个受管的模型配置（加密入库），
 * 之后就在管理后台维护即可；已有配置时不再覆盖。
 */
@Component
@Order(10)
class AiProviderSeeder(
    private val mapper: AiProviderMapper,
    private val service: AiProviderService,
    environment: Environment,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(AiProviderSeeder::class.java)

    private val apiKey: String = environment.getProperty("app.deepseek.api-key", "")?.trim().orEmpty()
    private val baseUrl: String = environment.getProperty("app.deepseek.base-url", "https://api.deepseek.com") ?: "https://api.deepseek.com"
    private val model: String = environment.getProperty("app.deepseek.model", "deepseek-chat") ?: "deepseek-chat"

    override fun run(args: ApplicationArguments) {
        if (mapper.countAll() > 0) return
        if (apiKey.isBlank()) {
            log.info("未配置任何 AI 平台，也未提供 DEEPSEEK_API_KEY；Agent 将使用本地规则判定（可在管理后台添加模型）")
            return
        }
        val created = service.create(
            AiProviderRequest(
                provider = "deepseek",
                name = "DeepSeek 官方",
                baseUrl = baseUrl,
                model = model,
                apiKey = apiKey,
                priority = 10,
                status = 1,
                timeoutSeconds = 60,
            )
        )
        log.info("seeded ai provider from environment: {} ({})", created.name, created.model)
    }
}
