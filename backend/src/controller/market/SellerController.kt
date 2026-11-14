package com.pgigi.agentclouddisk.controller.market

import com.pgigi.agentclouddisk.agent.ScanProgressRegistry
import com.pgigi.agentclouddisk.agent.market.MarketService
import com.pgigi.agentclouddisk.agent.market.ProductIntentParser
import com.pgigi.agentclouddisk.agent.market.ProductTemplateRegistry
import com.pgigi.agentclouddisk.agent.market.SellerAgentService
import com.pgigi.agentclouddisk.auth.CurrentUser
import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.common.ApiResult
import com.pgigi.agentclouddisk.mapper.UserFileMapper
import com.pgigi.agentclouddisk.mapper.market.DataProductMapper
import com.pgigi.agentclouddisk.model.FolderNodeDto
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 卖方视图：Agent 加工原始数据 → 数据产品 → 上架 */
@RestController
@RequestMapping("/api/seller")
class SellerController(
    private val sellerAgent: SellerAgentService,
    private val marketService: MarketService,
    private val productMapper: DataProductMapper,
    private val userFileMapper: UserFileMapper,
    private val progressRegistry: ScanProgressRegistry,
    private val templates: ProductTemplateRegistry,
    private val intentParser: ProductIntentParser,
) {

    @GetMapping("/templates")
    fun templates(): ApiResult<List<Map<String, Any?>>> = ApiResult.ok(templates.all().map {
        linkedMapOf(
            "key" to it.key,
            "label" to it.label,
            "domain" to it.domain,
            "description" to it.description,
            "fields" to it.fields.map { field -> mapOf("key" to field.key, "label" to field.label, "kind" to field.kind) },
            "groupBy" to it.groupBy,
            "measures" to it.measures,
            "tags" to it.tags,
            "aggregateLabel" to it.aggregateLabel,
        )
    })

    /**
     * 普通用户用一句话描述想要的产品，Agent 替他选模板。
     * 不需要用户懂"模板/字段/聚合维度"。
     */
    @PostMapping("/intent")
    fun intent(@RequestBody body: IntentRequest): ApiResult<ProductIntentParser.Intent> =
        ApiResult.ok(intentParser.parse(body.description ?: "", body.aiEnabled != false))

    data class IntentRequest(val description: String? = null, val aiEnabled: Boolean? = null)

    /** 可授权的文件夹树（沿用网盘目录） */
    @GetMapping("/folders")
    fun folders(): ApiResult<List<FolderNodeDto>> =
        ApiResult.ok(userFileMapper.listFolders(CurrentUser.id()).map {
            FolderNodeDto(it.id ?: 0, it.parentId, it.fileName, it.childCount)
        })

    @GetMapping("/products")
    fun products(): ApiResult<Any> = ApiResult.ok(sellerAgent.listProducts(CurrentUser.id()))

    @GetMapping("/products/{id}")
    fun product(@PathVariable id: Long): ApiResult<Any> {
        val product = sellerAgent.getProduct(CurrentUser.id(), id)
        val tasks = sellerAgent.buildTasks(id)
        val latest = tasks.firstOrNull()
        val items = latest?.id?.let { sellerAgent.buildItems(it) } ?: emptyList()
        return ApiResult.ok(
            linkedMapOf(
                "product" to product,
                "tasks" to tasks,
                "items" to items,
                "listing" to marketService.listMyListings(CurrentUser.id()).firstOrNull { it.productId == id },
            )
        )
    }

    @PostMapping("/products")
    fun create(@RequestBody body: SellerAgentService.CreateProductRequest): ApiResult<Map<String, Any?>> {
        val handle = sellerAgent.createAndBuild(CurrentUser.id(), body)
        return ApiResult.ok(linkedMapOf("productId" to handle.productId, "taskId" to handle.taskId))
    }

    @PostMapping("/products/{id}/publish")
    fun publish(@PathVariable id: Long, @RequestBody body: MarketService.PublishRequest): ApiResult<Any> =
        ApiResult.ok(marketService.publish(CurrentUser.id(), id, body))

    @PostMapping("/products/{id}/delist")
    fun delist(@PathVariable id: Long): ApiResult<Map<String, Any?>> =
        ApiResult.ok(linkedMapOf("delisted" to marketService.delist(CurrentUser.id(), id)))

    @DeleteMapping("/products/{id}")
    fun delete(@PathVariable id: Long): ApiResult<Unit> {
        val product = sellerAgent.getProduct(CurrentUser.id(), id)
        marketService.delist(CurrentUser.id(), id)
        productMapper.delete(product.id ?: 0)
        return ApiResult.ok(Unit)
    }

    /** 构建进度（复用扫描进度注册表） */
    @GetMapping("/build/{taskId}/progress")
    fun progress(@PathVariable taskId: Long): ApiResult<Map<String, Any?>> {
        val live = progressRegistry.get(taskId)
            ?: return ApiResult.ok(
                mapOf(
                    "taskId" to taskId, "done" to true, "taskStatus" to 2, "percent" to 100,
                    "stage" to "已完成", "processed" to 0, "total" to 0, "events" to emptyList<Any>(),
                )
            )
        return ApiResult.ok(
            linkedMapOf(
                "taskId" to taskId,
                "taskStatus" to live.status,
                "stage" to live.stage,
                "currentFile" to live.currentFile,
                "processed" to live.processed,
                "total" to live.total,
                "success" to live.success,
                "fail" to live.fail,
                "percent" to if (live.total == 0) 0 else (live.processed * 100 / live.total),
                "done" to (live.status == 2 || live.status == 3),
                "message" to live.message,
                "events" to live.events.map {
                    mapOf(
                        "seq" to it.seq, "fileId" to it.fileId, "fileName" to it.fileName, "stage" to it.stage,
                        "parseMethod" to it.parseMethod, "ok" to it.ok, "riskTag" to it.riskTag, "detail" to it.detail,
                    )
                },
            )
        )
    }

    /** 卖方待履约 */
    @GetMapping("/deliveries")
    fun deliveries(): ApiResult<Any> = ApiResult.ok(marketService.listDeliveries(CurrentUser.id(), asSeller = true))

    @PostMapping("/deliveries/{id}/accept")
    fun acceptDelivery(@PathVariable id: Long): ApiResult<Any> =
        ApiResult.ok(marketService.acceptDelivery(CurrentUser.id(), id))

    @PostMapping("/deliveries/{id}/complete")
    fun completeDelivery(@PathVariable id: Long): ApiResult<Any> =
        ApiResult.ok(marketService.completeDelivery(CurrentUser.id(), id))
}
