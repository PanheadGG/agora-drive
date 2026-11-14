package com.pgigi.agentclouddisk.agent.market

import com.pgigi.agentclouddisk.agent.FileContentParser
import com.pgigi.agentclouddisk.agent.Json
import com.pgigi.agentclouddisk.agent.LlmClient
import com.pgigi.agentclouddisk.agent.ScanProgressRegistry
import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.mapper.UserFileMapper
import com.pgigi.agentclouddisk.mapper.market.DataProductMapper
import com.pgigi.agentclouddisk.model.FileRow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ExecutorService

/**
 * 卖方侧 Agent：把用户授权范围内的原始文件加工成"数据产品"。
 *
 * 与旧"扫描报告"的区别：产物是可上架的产品（聚合统计 + 描述 + 构建凭证），
 * 而不是"哪些文件匹配某个需求"。
 */
@Service
class SellerAgentService(
    private val userFileMapper: UserFileMapper,
    private val productMapper: DataProductMapper,
    private val contentParser: FileContentParser,
    private val builder: ProductBuilder,
    private val templates: ProductTemplateRegistry,
    private val intentParser: ProductIntentParser,
    private val llmClient: LlmClient,
    private val progressRegistry: ScanProgressRegistry,
    private val scanExecutor: ExecutorService,
) {

    private val log = LoggerFactory.getLogger(SellerAgentService::class.java)

    fun templates() = templates.all()

    fun listProducts(userId: Long): List<DataProduct> = productMapper.listByOwner(userId)

    fun getProduct(userId: Long, productId: Long): DataProduct {
        val product = productMapper.findById(productId) ?: throw ApiException(404, "产品不存在")
        if (product.ownerId != userId) throw ApiException(403, "只能查看自己的产品")
        return product
    }

    fun buildItems(taskId: Long) = productMapper.listItems(taskId)

    fun buildTasks(productId: Long) = productMapper.listTasks(productId)

    /** 创建产品骨架并立即异步构建 */
    fun createAndBuild(userId: Long, req: CreateProductRequest): BuildHandle {
        // 普通用户走「一句话描述 → Agent 选模板」；也允许直接指定模板
        val description = req.description?.trim().orEmpty()
        val resolvedKey = when {
            !req.templateKey.isNullOrBlank() -> req.templateKey!!
            description.isNotBlank() -> intentParser.parse(description, req.aiEnabled != false).templateKey
            else -> templates.all().first().key
        }
        val template = templates.byKey(resolvedKey) ?: throw ApiException(400, "未知的产品模板：" + resolvedKey)
        if (req.scopeFileIds.isEmpty()) throw ApiException(400, "请先勾选要加工的文件夹或文件")

        val files = LinkedHashMap<Long, FileRow>()
        for (scopeId in req.scopeFileIds.distinct()) {
            val rows = userFileMapper.expandRows(scopeId, userId)
            if (rows.isEmpty()) throw ApiException(400, "授权范围包含不存在或不属于本人的条目：" + scopeId)
            rows.filter { it.isFolder == 0 }.forEach { row -> row.id?.let { files.putIfAbsent(it, row) } }
        }
        if (files.isEmpty()) throw ApiException(400, "授权范围内没有可加工的文件")

        val title = req.title?.trim().orEmpty().ifEmpty {
            description.take(20).ifEmpty { template.label + " · " + java.time.LocalDate.now() }
        }
        val reportedAi = req.aiRequested && llmClient.available
        val productId = productMapper.insertShell(
            userId, title, template.key, template.label, "solo", if (reportedAi) 1 else 0,
        )
        val taskId = productMapper.insertTask(
            productId, userId, req.scopeFileIds.distinct().joinToString(",", "[", "]"),
            if (reportedAi) 1 else 0, if (reportedAi) llmClient.activeModel else null,
        )

        val progress = progressRegistry.create(taskId, files.size)
        progress.stage = "排队中"
        scanExecutor.submit {
            runBuild(userId, productId, taskId, template, files.values.toList(), reportedAi, progress)
        }
        log.info("product {} build submitted: files={}, template={}, ai={}", productId, files.size, template.key, reportedAi)
        return BuildHandle(productId, taskId)
    }

    data class BuildHandle(val productId: Long, val taskId: Long)

    private fun runBuild(
        userId: Long,
        productId: Long,
        taskId: Long,
        template: ProductTemplateRegistry.Template,
        files: List<FileRow>,
        aiEnabled: Boolean,
        progress: ScanProgressRegistry.Progress,
    ) {
        try {
            progress.status = 1
            productMapper.updateTask(taskId, 1, files.size, 0, 0)

            val parsed = ArrayList<Triple<FileRow, FileContentParser.ParsedFile, Int>>()
            var success = 0
            var fail = 0
            files.forEachIndexed { index, row ->
                progress.currentFile = row.fileName
                progress.stage = "加工原料（" + (index + 1) + "/" + files.size + "）"
                val file = contentParser.parse(row.sha256, row.fileName, row.totalSize)
                parsed += Triple(row, file, index)
                if (file.ok) success++ else fail++
                progress.processed = index + 1
                progress.addEvent(
                    row.id ?: 0, row.fileName,
                    if (file.ok) "已解析" else "跳过",
                    file.method, file.ok,
                    file.risk?.tag ?: if (file.copied) "网络搬运" else null,
                    if (file.ok) file.summary.take(70) else file.note,
                )
            }

            progress.stage = "按模板聚合（k-匿名 k=" + ProductBuilder.K_ANONYMITY + "）"
            progress.currentFile = null
            val extraction = builder.extract(template.key, parsed, aiEnabled)
            if (extraction.usedFiles.isEmpty()) {
                progress.message = "选中文件里没有与「" + template.label + "」匹配的内容"
            }

            progress.stage = "生成产品描述"
            val tags = builder.describeTags(template, extraction.rows, aiEnabled)

            val schema = Json.stringify(
                template.fields.map { linkedMapOf("key" to it.key, "label" to it.label, "kind" to it.kind) }
            )
            val quality = Json.stringify(extraction.quality)
            val sample = Json.stringify(extraction.sample)
            val receipt = Json.stringify(extraction.receipt)

            productMapper.updateBuilt(
                productId,
                schema,
                extraction.rowCount.toLong(),
                extraction.usedFiles.size,
                extraction.coverage.first,
                extraction.coverage.second,
                quality,
                Json.stringify(tags),
                sample,
                receipt,
                if (extraction.piiClean) "clean" else "risk",
                STATUS_DRAFT,
            )

            for (used in extraction.usedFiles) {
                productMapper.insertItem(taskId, used.fileId, used.sha256, used.fileName, used.parseMethod, used.rows, 1, null, null)
            }
            for (excluded in extraction.excludedFiles) {
                productMapper.insertItem(
                    taskId, excluded.fileId, excluded.sha256, excluded.fileName, excluded.parseMethod,
                    0, 2, excluded.riskTag, excluded.note,
                )
            }

            productMapper.updateTask(taskId, 2, files.size, success, fail)
            progress.processed = files.size
            progress.success = success
            progress.fail = fail
            progress.stage = "产品已生成，待上架"
            progress.finish(2)
            log.info(
                "product {} built: rawRows={} groups={} suppressed={} usedFiles={} excluded={}",
                productId, extraction.rowCount, extraction.rows.size,
                extraction.quality["suppressedGroups"], extraction.usedFiles.size, extraction.excludedFiles.size,
            )
        } catch (error: Exception) {
            log.error("product {} build failed", productId, error)
            runCatching { productMapper.updateTask(taskId, 3, files.size, progress.success, progress.fail) }
            progress.finish(3, error.message ?: error.javaClass.simpleName)
        }
    }

    /** 联合产品：由两个 Agent 的聚合贡献合并而成 */
    fun createJointProduct(
        ownerId: Long,
        template: ProductTemplateRegistry.Template,
        title: String,
        aggregate: List<Map<String, String>>,
        contributions: List<Pair<Long, CollabContribution>>,
        aiEnabled: Boolean,
        kThreshold: Int = ProductBuilder.K_ANONYMITY,
    ): Long {
        val productId = productMapper.insertShell(ownerId, title, template.key, template.label, "joint", if (aiEnabled) 1 else 0)
        val taskId = productMapper.insertTask(productId, ownerId, "[]", if (aiEnabled) 1 else 0, if (aiEnabled) llmClient.activeModel else null)

        val rows = aggregate
        val quality = linkedMapOf<String, Any?>(
            "rawRows" to contributions.sumOf { it.second.groupCount },
            "groups" to rows.size,
            "suppressedGroups" to contributions.sumOf { it.second.suppressedGroups },
            "kAnonymity" to kThreshold,
            "coverageRatio" to 100,
            "sourceFiles" to contributions.size,
            "joint" to true,
            "score" to (60 + rows.size.coerceAtMost(20) * 2).coerceAtMost(100),
        )
        val receipt = linkedMapOf<String, Any?>(
            "template" to template.key,
            "templateLabel" to template.label,
            "joint" to true,
            "aiEnabled" to aiEnabled,
            "model" to if (aiEnabled) llmClient.activeModel else null,
            "kAnonymity" to kThreshold,
            "contributors" to contributions.map {
                linkedMapOf(
                    "userId" to it.first,
                    "groups" to it.second.groupCount,
                    "suppressedGroups" to it.second.suppressedGroups,
                    "digest" to Json.stringify(it.second.aggregateJson).hashCode().toString(16),
                )
            },
        )
        val tags = if (aiEnabled) builder.describeTags(template, rows, true) else template.tags
        val schema = Json.stringify(template.fields.map { linkedMapOf("key" to it.key, "label" to it.label, "kind" to it.kind) })

        productMapper.updateBuilt(
            productId, schema, rows.size.toLong(), contributions.size,
            null, null, Json.stringify(quality), Json.stringify(tags),
            Json.stringify(rows.take(8)), Json.stringify(receipt), "clean", STATUS_DRAFT,
        )
        productMapper.updateTask(taskId, 2, contributions.size, contributions.size, 0)
        log.info("joint product {} created from {} contributions, groups={}", productId, contributions.size, rows.size)
        return productId
    }

    /**
     * 面向普通用户的建产品请求：
     * - description：一句话说想做什么，Agent 自己选模板（推荐路径）
     * - templateKey：高级用户可直接指定模板，二者给一个即可
     * - aiEnabled：默认开启，前端不再暴露该开关
     */
    data class CreateProductRequest(
        val description: String? = null,
        val templateKey: String? = null,
        val title: String? = null,
        val scopeFileIds: List<Long> = emptyList(),
        val aiEnabled: Boolean? = null,
    ) {
        /** 缺省视为开启：大模型参与是默认体验，不是需要用户理解的可选项 */
        val aiRequested: Boolean get() = aiEnabled != false
    }

    companion object {
        const val STATUS_DRAFT = 0
        const val STATUS_LISTED = 1
        const val STATUS_DELISTED = 2
    }
}
