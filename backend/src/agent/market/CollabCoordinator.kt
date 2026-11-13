package com.pgigi.agentclouddisk.agent.market

import com.pgigi.agentclouddisk.agent.FileContentParser
import com.pgigi.agentclouddisk.agent.Json
import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.mapper.UserFileMapper
import com.pgigi.agentclouddisk.mapper.market.MarketMapper
import com.pgigi.agentclouddisk.model.FileRow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Agent 间协同：两个卖方各自在本地聚合，只交换**聚合结果**，合并成联合产品。
 *
 * 协议四步：invite → accept → contribute（双方各自本地聚合）→ merge（需双方确认）。
 *
 * 防泄露设计：
 * 1. 交换的只有分组聚合，从不传原始行；
 * 2. 合并时取**交集分组**（两边都出现的分组），单边独有的分组不会出现在联合产品里；
 * 3. 联合产品再带一层 k-匿名统计，组内样本过少会被丢弃。
 */
@Service
class CollabCoordinator(
    private val marketMapper: MarketMapper,
    private val userFileMapper: UserFileMapper,
    private val sellerAgent: SellerAgentService,
    private val builder: ProductBuilder,
    private val templates: ProductTemplateRegistry,
    private val contentParser: FileContentParser,
) {

    private val log = LoggerFactory.getLogger(CollabCoordinator::class.java)

    fun invite(initiatorId: Long, req: InviteRequest): CollabSession {
        val template = templates.byKey(req.templateKey) ?: throw ApiException(400, "未知的产品模板：" + req.templateKey)
        if (req.partnerId == initiatorId) throw ApiException(400, "不能和自己协同")
        if (req.partnerId <= 0) throw ApiException(400, "请选择协同伙伴")
        val id = marketMapper.insertCollab(
            template.key, template.label,
            req.title.trim().ifEmpty { template.label + " · 联合产品" },
            initiatorId, req.partnerId, STATE_INVITED, req.kThreshold.coerceIn(2, 50),
            req.message.trim().ifEmpty { null },
        )
        log.info("collab {} invited: {} -> {} template={}", id, initiatorId, req.partnerId, template.key)
        return session(id)
    }

    fun list(userId: Long, isAdmin: Boolean): List<CollabSession> =
        if (isAdmin) marketMapper.listAllCollabs() else marketMapper.listCollabsForUser(userId)

    fun detail(userId: Long, sessionId: Long, isAdmin: Boolean): CollabDetail {
        val session = session(sessionId)
        if (!isAdmin && session.initiatorId != userId && session.partnerId != userId) {
            throw ApiException(403, "无权查看该协同会话")
        }
        val contributions = marketMapper.listContributions(sessionId)
        return CollabDetail(session, contributions, mergedPreview(session, contributions))
    }

    fun accept(userId: Long, sessionId: Long): CollabSession {
        val session = session(sessionId)
        if (session.partnerId != userId) throw ApiException(403, "只有被邀请方可以接受")
        if (session.state != STATE_INVITED) throw ApiException(400, "当前状态不接受该操作")
        marketMapper.updateCollabState(sessionId, STATE_ACCEPTED)
        return session(sessionId)
    }

    fun reject(userId: Long, sessionId: Long): CollabSession {
        val session = session(sessionId)
        if (session.partnerId != userId && session.initiatorId != userId) throw ApiException(403, "无权操作")
        if (session.state >= STATE_MERGED) throw ApiException(400, "已合并的会话不能取消")
        marketMapper.updateCollabState(sessionId, STATE_REJECTED)
        return session(sessionId)
    }

    /** 一方在本机聚合自己的授权范围，只提交聚合结果 */
    fun contribute(userId: Long, sessionId: Long, req: ContributeRequest): CollabDetail {
        val session = session(sessionId)
        if (session.initiatorId != userId && session.partnerId != userId) throw ApiException(403, "无权贡献")
        if (session.state != STATE_ACCEPTED && session.state != STATE_CONTRIBUTING) {
            throw ApiException(400, "需对方接受后才能贡献，当前状态：" + stateLabel(session.state))
        }
        val template = templates.byKey(session.templateKey) ?: throw ApiException(400, "模板不存在")
        if (req.scopeFileIds.isEmpty()) throw ApiException(400, "请勾选要参与联合产品的文件夹")

        val files = LinkedHashMap<Long, FileRow>()
        for (scopeId in req.scopeFileIds.distinct()) {
            userFileMapper.expandRows(scopeId, userId).filter { it.isFolder == 0 }
                .forEach { row -> row.id?.let { files.putIfAbsent(it, row) } }
        }
        if (files.isEmpty()) throw ApiException(400, "授权范围内没有文件")

        val parsed = files.values.mapIndexed { index, row ->
            Triple(row, contentParser.parse(row.sha256, row.fileName, row.totalSize), index)
        }
        val extraction = builder.extract(template.key, parsed, req.aiEnabled == true)

        marketMapper.upsertContribution(
            sessionId, userId,
            Json.stringify(extraction.rows),
            extraction.usedFiles.sumOf { it.rows },
            (extraction.quality["suppressedGroups"] as? Int) ?: 0,
            if (req.approve) 1 else 0,
        )
        if (session.state == STATE_ACCEPTED) marketMapper.updateCollabState(sessionId, STATE_CONTRIBUTING)
        log.info("collab {} contribution from {}: groups={}", sessionId, userId, extraction.rows.size)
        return detail(userId, sessionId, false)
    }

    /** 双方都确认后合并；联合产品由发起方持有 */
    fun merge(userId: Long, sessionId: Long): CollabDetail {
        val session = session(sessionId)
        if (session.initiatorId != userId && session.partnerId != userId) throw ApiException(403, "无权合并")
        val contributions = marketMapper.listContributions(sessionId)
        if (contributions.size < 2) throw ApiException(400, "双方都需先提交聚合结果")
        if (contributions.any { it.approved != 1 }) throw ApiException(400, "双方都需确认后才能合并")

        val merged = mergedPreview(session, contributions)
        if (merged.isEmpty()) throw ApiException(400, "两边没有共同分组，无法合并（联合产品要求双方覆盖同一维度）")
        val template = templates.byKey(session.templateKey) ?: throw ApiException(400, "模板不存在")
        val productId = sellerAgent.createJointProduct(
            session.initiatorId, template, session.title, merged, contributions.map { it.userId to it },
            true, session.kThreshold,
        )
        marketMapper.bindCollabProduct(sessionId, productId, STATE_MERGED)
        log.info("collab {} merged into product {}: groups={}", sessionId, productId, merged.size)
        return detail(userId, sessionId, false)
    }

    /** 交集合并：分组必须在每一方都存在，单边独有分组不进联合产品 */
    private fun mergedPreview(session: CollabSession, contributions: List<CollabContribution>): List<Map<String, String>> {
        if (contributions.size < 2) return emptyList()
        val template = templates.byKey(session.templateKey) ?: return emptyList()
        val groupKey = template.groupBy.firstOrNull() ?: return emptyList()

        val perSide = contributions.map { contribution ->
            (Json.asList(Json.parse(contribution.aggregateJson)) ?: emptyList())
                .mapNotNull { Json.asMap(it) }
                .associateBy { Json.asString(it[groupKey]) ?: "" }
        }
        val common = perSide.map { it.keys }.reduce { acc, keys -> acc.intersect(keys) }
            .filter { it.isNotEmpty() && it != "未分类" }
        if (common.isEmpty()) return emptyList()

        return common.map { key ->
            val entry = linkedMapOf<String, String>()
            entry[groupKey] = key
            val perSideRows = perSide.mapNotNull { it[key] }
            (template.measures + "sample_count").forEach { measure ->
                val values = perSideRows.mapNotNull { Json.asString(it[measure])?.toDoubleOrNull() }
                if (values.isNotEmpty()) entry[measure] = "%.2f".format(values.average())
            }
            entry["sources"] = perSideRows.size.toString()
            entry
        }.sortedByDescending { it["sample_count"]?.toDoubleOrNull() ?: 0.0 }
    }

    private fun session(id: Long): CollabSession = marketMapper.findCollab(id) ?: throw ApiException(404, "协同会话不存在")

    data class InviteRequest(
        val partnerId: Long = 0,
        val templateKey: String = "",
        val title: String = "",
        val message: String = "",
        val kThreshold: Int = 5,
    )

    data class ContributeRequest(
        val scopeFileIds: List<Long> = emptyList(),
        val aiEnabled: Boolean? = null,
        val approve: Boolean = false,
    )

    data class CollabDetail(
        val session: CollabSession,
        val contributions: List<CollabContribution>,
        val mergedPreview: List<Map<String, String>>,
    )

    companion object {
        const val STATE_INVITED = 0
        const val STATE_ACCEPTED = 1
        const val STATE_CONTRIBUTING = 2
        const val STATE_MERGED = 3
        const val STATE_REJECTED = 4

        fun stateLabel(state: Int): String = when (state) {
            0 -> "待对方接受"
            1 -> "已接受，待双方贡献"
            2 -> "双方贡献中"
            3 -> "已合并为联合产品"
            else -> "已取消"
        }
    }
}
