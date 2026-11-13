package com.pgigi.agentclouddisk.agent.market

import com.pgigi.agentclouddisk.agent.BountyStructureParser
import com.pgigi.agentclouddisk.agent.Json
import com.pgigi.agentclouddisk.agent.LlmMatchJudge
import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.mapper.market.DataProductMapper
import com.pgigi.agentclouddisk.mapper.market.MarketMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 数据市场：上架、采购需求、匹配推送、交付（最小化）。
 *
 * 匹配发生在**市场侧**，用的是产品描述 × 结构化需求，卖方的原始数据和 Agent 不参与。
 */
@Service
class MarketService(
    private val marketMapper: MarketMapper,
    private val productMapper: DataProductMapper,
    private val sellerAgent: SellerAgentService,
    private val matchJudge: LlmMatchJudge,
    private val templates: ProductTemplateRegistry,
) {

    private val log = LoggerFactory.getLogger(MarketService::class.java)

    // ---------- 卖方：上架 / 下架 ----------

    fun browse(req: ListRequest): List<ProductListing> {
        val all = marketMapper.listActiveListings()
        return all.filter { listing ->
            (req.domain.isBlank() || listing.domain == req.domain) &&
                (req.keyword.isBlank() || matchText(listing).contains(req.keyword)) &&
                (req.maxPriceCents <= 0 || listing.priceCents <= req.maxPriceCents)
        }
    }

    fun detail(listingId: Long): ListingDetail {
        val listing = marketMapper.findListingById(listingId) ?: throw ApiException(404, "货源不存在")
        val product = listing.productId.takeIf { it > 0 }?.let { productMapper.findById(it) } ?: throw ApiException(404, "产品不存在")
        // 市场只暴露描述与脱敏样例，绝不返回原料文件名/内容
        return ListingDetail(listing, product, product.sampleJson, product.qualityJson, product.buildReceipt)
    }

    fun listMyListings(userId: Long): List<ProductListing> = marketMapper.listListingsBySeller(userId)

    fun publish(userId: Long, productId: Long, req: PublishRequest): ProductListing {
        val product = productMapper.findById(productId) ?: throw ApiException(404, "产品不存在")
        if (product.ownerId != userId) throw ApiException(403, "只能上架自己的产品")
        if (product.status == SellerAgentService.STATUS_LISTED) throw ApiException(400, "该产品已在上架中")
        if (product.piiStatus != "clean") throw ApiException(400, "产品未通过合规检查，不能上架")
        if (product.rowCount <= 0) throw ApiException(400, "产品还没有聚合结果，请先构建")

        val template = templates.byKey(product.templateKey) ?: templates.all().first()
        val tags = Json.asStringList(Json.parse(product.tagsJson))
        val fields = (Json.asList(Json.parse(product.schemaJson)) ?: emptyList())
            .mapNotNull { Json.asString(Json.asMap(it)?.get("label")) }
        val qualityScore = (Json.asMap(Json.parse(product.qualityJson))?.get("score")?.let { Json.asInt(it, 0) }) ?: 0

        val listingId = marketMapper.insertListing(
            productId, userId,
            req.title.trim().ifEmpty { product.title },
            req.summary.trim().ifEmpty { template.description },
            Json.stringify(fields), Json.stringify(tags), template.domain,
            product.coverageStart, product.coverageEnd, product.rowCount, qualityScore,
            req.priceCents.coerceAtLeast(0), req.licenseType.ifBlank { "internal" },
        )
        productMapper.updateStatus(productId, SellerAgentService.STATUS_LISTED)
        log.info("product {} listed as {} (price={})", productId, listingId, req.priceCents)
        return marketMapper.findListingById(listingId) ?: throw ApiException(500, "上架失败")
    }

    fun delist(userId: Long, productId: Long): Int {
        val product = productMapper.findById(productId) ?: throw ApiException(404, "产品不存在")
        if (product.ownerId != userId) throw ApiException(403, "只能下架自己的产品")
        val affected = marketMapper.delistByProduct(productId)
        productMapper.updateStatus(productId, SellerAgentService.STATUS_DELISTED)
        val invalidated = marketMapper.invalidateMatchesOfProduct(productId)
        log.info("product {} delisted, {} matches invalidated", productId, invalidated)
        return affected
    }

    // ---------- 买方：需求 / 匹配 / 推送 ----------

    fun createDemand(userId: Long, req: DemandRequest): MarketDemand {
        if (req.title.isBlank()) throw ApiException(400, "需求标题不能为空")
        if (req.rawDesc.trim().length < 10) throw ApiException(400, "需求描述太短，至少 10 个字")
        val requirement = BountyStructureParser.parse(req.title + " " + req.rawDesc)
        val id = marketMapper.insertDemand(
            userId, req.title.trim(), req.rawDesc.trim(), requirement.toJson(), req.budgetCents.coerceAtLeast(0),
        )
        val demand = marketMapper.findDemand(id) ?: throw ApiException(500, "需求创建失败")
        // 发布后立即匹配一次，体现"主动推送"
        matchDemand(userId, id)
        return demand
    }

    fun listDemands(userId: Long): List<MarketDemand> = marketMapper.listDemandsByBuyer(userId)

    fun listActiveDemands(): List<MarketDemand> = marketMapper.listActiveDemands()

    fun matchDemand(userId: Long, demandId: Long): List<MarketMatch> {
        val demand = marketMapper.findDemand(demandId) ?: throw ApiException(404, "需求不存在")
        if (demand.buyerId != userId) throw ApiException(403, "只能给自己的需求匹配")
        val requirement = BountyStructureParser.fromJson(demand.structuredJson)
            ?: BountyStructureParser.parse(demand.title + " " + demand.rawDesc)

        val listings = marketMapper.listActiveListings().filter { it.sellerId != userId }
        val scored = listings.mapNotNull { listing ->
            val result = scoreListing(requirement, listing)
            if (result.score <= 0) null else Triple(listing, result.score, result.reasons)
        }.sortedByDescending { it.second }.take(MAX_PUSH)

        for ((listing, score, reasons) in scored) {
            marketMapper.upsertMatch(demandId, listing.id ?: 0, score, Json.stringify(reasons))
        }
        log.info("demand {} matched {} listings", demandId, scored.size)
        return marketMapper.listMatchesByDemand(demandId)
    }

    /** 三阶段匹配：硬过滤 → 语义打分 → 排序，并生成可读的命中理由 */
    private fun scoreListing(requirement: BountyStructureParser.Requirement, listing: ProductListing): Scored {
        val text = matchText(listing)
        val reasons = ArrayList<String>()

        // 1) 硬过滤：品类 / 关键词完全无交集直接排除
        val keywordHits = requirement.matchKeywords.filter { text.contains(it) }
        if (keywordHits.isEmpty()) {
            val domainHit = requirement.domainLabel.isNotBlank() && text.contains(requirement.domainLabel.take(2))
            if (!domainHit) return Scored(0, emptyList())
            reasons += "品类接近：" + listing.domain
        } else {
            reasons += "命中关键词：" + keywordHits.take(4).joinToString("、")
        }

        // 2) 时间覆盖
        var coverageBonus = 0
        val coverageEnd = listing.coverageEnd
        if (requirement.yearLimit != null && coverageEnd != null) {
            val year = coverageEnd.take(4).toIntOrNull()
            val currentYear = java.time.LocalDate.now().year
            if (year != null && currentYear - year <= requirement.yearLimit) {
                coverageBonus = 12
                reasons += "时间覆盖符合（截至 " + coverageEnd + "）"
            }
        }

        // 3) 排除词命中 → 直接排除
        val excluded = requirement.excludeKeywords.filter { it.isNotBlank() && text.contains(it) }
        if (excluded.isNotEmpty()) return Scored(0, emptyList())

        // 4) 综合打分
        var score = 40
        score += (keywordHits.size.coerceAtMost(5) * 6)
        score += coverageBonus
        score += (listing.qualityScore / 10)
        if (listing.rowCount >= 100) { score += 6; reasons += "覆盖量 " + listing.rowCount + " 条" }
        score += (requirement.formats.size.coerceAtMost(3) * 2)
        if (listing.licenseType == "open") { score += 3; reasons += "授权宽松" }
        if (listing.priceCents == 0L) { score += 3; reasons += "免费货源" }

        return Scored(score.coerceIn(0, 100), reasons.distinct().take(5))
    }

    private fun matchText(listing: ProductListing): String =
        listOf(listing.title, listing.summary, listing.domain, listing.tagsJson, listing.fieldList).joinToString(" ")

    fun listPushes(userId: Long): List<MarketMatch> = marketMapper.listMatchesForBuyer(userId)

    fun markRead(userId: Long, matchId: Long): MarketMatch {
        val match = marketMapper.findMatch(matchId) ?: throw ApiException(404, "推送记录不存在")
        val demand = marketMapper.findDemand(match.demandId) ?: throw ApiException(404, "需求不存在")
        if (demand.buyerId != userId) throw ApiException(403, "无权操作")
        marketMapper.updateMatchState(matchId, if (match.state == 0) 1 else match.state, null)
        return marketMapper.findMatch(matchId) ?: match
    }

    // ---------- 交付（最小化） ----------

    fun requestDelivery(userId: Long, matchId: Long, note: String?): DeliveryRequest {
        val match = marketMapper.findMatch(matchId) ?: throw ApiException(404, "推送记录不存在")
        val demand = marketMapper.findDemand(match.demandId) ?: throw ApiException(404, "需求不存在")
        if (demand.buyerId != userId) throw ApiException(403, "只能对自己的推送申请交付")
        if (match.state == 3) throw ApiException(400, "该货源已下架")
        val listing = marketMapper.findListingById(match.listingId) ?: throw ApiException(404, "货源不存在")

        val id = marketMapper.insertDelivery(matchId, userId, listing.sellerId, note?.trim()?.ifEmpty { null })
        marketMapper.updateMatchState(matchId, 2, "已申请交付")
        log.info("delivery {} requested: match={} buyer={} seller={}", id, matchId, userId, listing.sellerId)
        return marketMapper.findDelivery(id) ?: throw ApiException(500, "交付申请失败")
    }

    fun acceptDelivery(userId: Long, deliveryId: Long): DeliveryRequest {
        val delivery = marketMapper.findDelivery(deliveryId) ?: throw ApiException(404, "交付申请不存在")
        if (delivery.sellerId != userId) throw ApiException(403, "只有卖方可以受理")
        if (delivery.status != DELIVERY_PENDING) throw ApiException(400, "当前状态不可受理")
        marketMapper.updateDeliveryStatus(deliveryId, DELIVERY_ACCEPTED)
        return marketMapper.findDelivery(deliveryId) ?: delivery
    }

    fun completeDelivery(userId: Long, deliveryId: Long): DeliveryRequest {
        val delivery = marketMapper.findDelivery(deliveryId) ?: throw ApiException(404, "交付申请不存在")
        if (delivery.sellerId != userId) throw ApiException(403, "只有卖方可以标记完成")
        if (delivery.status != DELIVERY_ACCEPTED) throw ApiException(400, "需先受理")
        marketMapper.updateDeliveryStatus(deliveryId, DELIVERY_DELIVERED)
        return marketMapper.findDelivery(deliveryId) ?: delivery
    }

    fun listDeliveries(userId: Long, asSeller: Boolean): List<DeliveryRequest> =
        if (asSeller) marketMapper.listDeliveriesForSeller(userId) else marketMapper.listDeliveriesForBuyer(userId)

    fun listAllDeliveries(): List<DeliveryRequest> = marketMapper.listAllDeliveries()

    fun stats(): Map<String, Any?> = linkedMapOf(
        "activeListings" to marketMapper.countActiveListings(),
        "unreadPushes" to marketMapper.countAllUnread(),
        "pendingDeliveries" to marketMapper.countPendingDeliveries(),
        "products" to productMapper.countAll(),
        "collabs" to marketMapper.countCollabs(),
    )

    private data class Scored(val score: Int, val reasons: List<String>)

    data class ListRequest(
        val keyword: String = "",
        val domain: String = "",
        val maxPriceCents: Long = 0,
    )

    data class PublishRequest(
        val title: String = "",
        val summary: String = "",
        val priceCents: Long = 0,
        val licenseType: String = "internal",
    ) {
        val price: Long get() = priceCents
    }

    data class DemandRequest(
        val title: String = "",
        val rawDesc: String = "",
        val budgetCents: Long = 0,
    )

    data class ListingDetail(
        val listing: ProductListing,
        val product: DataProduct,
        val sampleJson: String,
        val qualityJson: String,
        val buildReceipt: String,
    )

    companion object {
        private const val MAX_PUSH = 6
        const val DELIVERY_PENDING = 0
        const val DELIVERY_ACCEPTED = 1
        const val DELIVERY_DELIVERED = 2
    }
}
