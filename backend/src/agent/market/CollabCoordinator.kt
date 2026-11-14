package com.pgigi.agentclouddisk.agent.market

import com.pgigi.agentclouddisk.agent.FileContentParser
import com.pgigi.agentclouddisk.agent.Json
import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.mapper.UserFileMapper
import com.pgigi.agentclouddisk.mapper.market.CollabMapper
import com.pgigi.agentclouddisk.model.FileRow
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import java.security.SecureRandom

/**
 * 多 Agent 协同（按「协同号 + 协同密码」加入，产品随成员增加而动态增大）。
 *
 * 流程：
 *  1. A 用一句话描述 + 勾选文件夹 → 创建协同，拿到协同号与协同密码；
 *  2. B 凭协同号 + 密码 → 看到邀请 → 勾选自己的文件夹 → 加入；
 *  3. 每个成员各自在本地聚合（只上传分组统计，不传原始行）；
 *  4. 产品随成员加入持续增大，任何人可随时刷新看到最新规模；
 *  5. 最终由**发起人**上架。
 *
 * 防泄露：交换的只有分组聚合；联合结果按分组求并集，单边独有分组也在，但都不含原始行。
 */
@Service
class CollabCoordinator(
    private val collabMapper: CollabMapper,
    private val userFileMapper: UserFileMapper,
    private val sellerAgent: SellerAgentService,
    private val builder: ProductBuilder,
    private val templates: ProductTemplateRegistry,
    private val contentParser: FileContentParser,
    private val passwordEncoder: BCryptPasswordEncoder,
    private val intentParser: ProductIntentParser,
    private val marketService: MarketService,
) {

    private val log = LoggerFactory.getLogger(CollabCoordinator::class.java)
    private val random = SecureRandom()

    // ---------- 创建 / 加入 ----------

    /** A 发起：返回协同号与明文协同密码（密码只在此刻返回一次） */
    fun create(userId: Long, req: CreateRequest): CreateResult {
        val description = req.description?.trim().orEmpty()
        val resolvedKey = when {
            !req.templateKey.isNullOrBlank() -> req.templateKey!!
            description.isNotBlank() -> intentParser.parse(description, req.aiEnabled != false).templateKey
            else -> templates.all().first().key
        }
        val template = templates.byKey(resolvedKey) ?: throw ApiException(400, "未知的产品模板：" + resolvedKey)
        if (req.scopeFileIds.isEmpty()) throw ApiException(400, "请勾选要参与协同的文件夹")

        val files = collectFiles(userId, req.scopeFileIds)
        if (files.isEmpty()) throw ApiException(400, "选中的文件夹里没有文件")

        val code = generateCode()
        val password = generatePassword()
        val title = req.title?.trim().orEmpty().ifEmpty {
            description.take(20).ifEmpty { template.label + " · 协同产品" }
        }

        val sessionId = collabMapper.insertSession(
            code, passwordEncoder.encode(password)!!, template.key, template.label, title,
            description, userId, STATE_ACTIVE, req.threshold.coerceIn(2, 50),
            req.message?.trim()?.ifEmpty { null },
        )
        collabMapper.upsertMember(
            sessionId, userId, ROLE_OWNER, Json.stringify(req.scopeFileIds.distinct()),
            files.size, MEMBER_JOINED,
        )

        // 发起人立刻贡献自己那一份，产品一开始就有内容
        contributeInternal(sessionId, userId, req.scopeFileIds, req.aiEnabled != false)

        log.info("collab {} created by {} code={} files={}", sessionId, userId, code, files.size)
        return CreateResult(
            sessionId = sessionId,
            collabCode = code,
            collabPassword = password,
            templateKey = template.key,
            templateLabel = template.label,
            title = title,
            memberCount = 1,
            detail = detail(userId, sessionId, false),
        )
    }

    /** B 凭协同号 + 密码获取邀请信息（未加入前也能看到要做什么） */
    fun peek(userId: Long, req: PeekRequest): PeekResult {
        val session = verify(req.collabCode, req.collabPassword)
        val template = templates.byKey(session.templateKey)
        val members = collabMapper.listMembers(session.id ?: 0)
        val joined = members.any { it.userId == userId }
        return PeekResult(
            sessionId = session.id ?: 0,
            collabCode = session.collabCode ?: "",
            title = session.title,
            description = session.description,
            templateKey = session.templateKey,
            templateLabel = session.templateLabel,
            templateDescription = template?.description ?: "",
            fields = template?.fields?.map { it.label } ?: emptyList(),
            ownerName = session.initiatorName ?: "",
            memberCount = members.size,
            memberNames = members.mapNotNull { it.userName },
            state = session.state,
            stateLabel = stateLabel(session.state),
            alreadyJoined = joined,
            canJoin = !joined && session.state == STATE_ACTIVE,
        )
    }

    /** B 加入：勾选自己的文件夹后立即贡献 */
    fun join(userId: Long, req: JoinRequest): CollabDetail {
        val session = verify(req.collabCode, req.collabPassword)
        if (session.state != STATE_ACTIVE) throw ApiException(400, "该协同已结束，无法加入")
        val sessionId = session.id ?: 0

        val files = collectFiles(userId, req.scopeFileIds)
        if (files.isEmpty()) throw ApiException(400, "选中的文件夹里没有文件")

        collabMapper.upsertMember(
            sessionId, userId, ROLE_MEMBER, Json.stringify(req.scopeFileIds.distinct()),
            files.size, MEMBER_JOINED,
        )
        if (session.partnerId == session.initiatorId) {
            collabMapper.setPartner(sessionId, userId)
        }
        contributeInternal(sessionId, userId, req.scopeFileIds, req.aiEnabled != false)
        log.info("collab {} joined by {} files={}", sessionId, userId, files.size)
        return detail(userId, sessionId, false)
    }

    /** 成员追加/更新自己的文件夹，产品随之增大 */
    fun addFiles(userId: Long, sessionId: Long, req: AddFilesRequest): CollabDetail {
        val session = session(sessionId)
        requireMember(sessionId, userId)
        if (session.state != STATE_ACTIVE) throw ApiException(400, "该协同已结束")
        val files = collectFiles(userId, req.scopeFileIds)
        if (files.isEmpty()) throw ApiException(400, "选中的文件夹里没有文件")

        collabMapper.upsertMember(
            sessionId, userId,
            if (session.ownerId == userId) ROLE_OWNER else ROLE_MEMBER,
            Json.stringify(req.scopeFileIds.distinct()), files.size, MEMBER_JOINED,
        )
        contributeInternal(sessionId, userId, req.scopeFileIds, req.aiEnabled != false)
        return detail(userId, sessionId, false)
    }

    // ---------- 查询 ----------

    fun list(userId: Long, isAdmin: Boolean): List<SessionView> =
        (if (isAdmin) collabMapper.listAll() else collabMapper.listForUser(userId)).map { SessionView.of(it) }

    fun detail(userId: Long, sessionId: Long, isAdmin: Boolean): CollabDetail {
        val session = session(sessionId)
        if (!isAdmin) requireMember(sessionId, userId)
        val members = collabMapper.listMembers(sessionId)
        val contributions = members.mapNotNull { member ->
            collabMapper.findMember(sessionId, member.userId)?.let { member to it }
        }
        return CollabDetail(
            session = SessionView.of(session),
            members = members,
            aggregate = mergedAggregate(session, members),
            totalRows = members.sumOf { it.rowsContributed },
            totalGroups = mergeGroupCount(session, members),
            canPublish = session.ownerId == userId && session.state == STATE_ACTIVE && mergeGroupCount(session, members) > 0,
            ownerViewer = session.ownerId == userId,
        )
    }

    /** 成员各自贡献的分组统计（原始行不出本机） */
    fun memberAggregate(userId: Long, sessionId: Long): List<Map<String, Any?>> {
        requireMember(sessionId, userId)
        val member = collabMapper.findMember(sessionId, userId) ?: return emptyList()
        return memberAggregateOf(sessionId, member.userId)
    }

    // ---------- 上架（发起人） ----------

    fun publish(userId: Long, sessionId: Long, req: PublishRequest): Long {
        val session = session(sessionId)
        if (session.ownerId != userId) throw ApiException(403, "只有发起人可以上架协同产品")
        if (session.state != STATE_ACTIVE) throw ApiException(400, "该协同已结束")
        val members = collabMapper.listMembers(sessionId)
        if (members.size < 2) throw ApiException(400, "至少需要 2 个成员参与才能上架")

        val aggregate = mergedAggregate(session, members)
        if (aggregate.isEmpty()) throw ApiException(400, "还没有可用的聚合结果，请先让成员加入")

        val template = templates.byKey(session.templateKey) ?: throw ApiException(400, "模板不存在")
        val contributions = members.map { member ->
            member.userId to CollabContribution(
                id = member.id,
                sessionId = sessionId,
                userId = member.userId,
                aggregateJson = Json.stringify(memberAggregateOf(sessionId, member.userId)),
                groupCount = member.rowsContributed,
                suppressedGroups = 0,
                approved = if (member.status == MEMBER_CONTRIBUTED) 1 else 0,
                userName = member.userName,
            )
        }
        val productId = sellerAgent.createJointProduct(
            session.ownerId, template, session.title, aggregate, contributions, true, session.kThreshold,
        )
        collabMapper.bindProduct(sessionId, productId, STATE_MERGED)

        marketService.publish(
            session.ownerId, productId,
            MarketService.PublishRequest(
                title = req.title?.trim()?.ifEmpty { session.title } ?: session.title,
                summary = req.summary?.trim()?.ifEmpty { "由 " + members.size + " 位成员的 Agent 协同生成" }
                    ?: ("由 " + members.size + " 位成员的 Agent 协同生成"),
                priceCents = req.price.coerceAtLeast(0),
                licenseType = req.licenseType?.ifBlank { "internal" } ?: "internal",
            ),
        )
        log.info("collab {} published as product {} by {}", sessionId, productId, userId)
        return productId
    }

    fun close(userId: Long, sessionId: Long): SessionView {
        val session = session(sessionId)
        if (session.ownerId != userId) throw ApiException(403, "只有发起人可以结束协同")
        if (session.state == STATE_MERGED) throw ApiException(400, "已上架的协同不能结束")
        collabMapper.updateState(sessionId, STATE_CLOSED)
        return SessionView.of(session(sessionId))
    }

    // ---------- 内部 ----------

    /** 本地聚合 + 只上报分组统计 */
    private fun contributeInternal(
        sessionId: Long,
        userId: Long,
        scopeFileIds: List<Long>,
        aiEnabled: Boolean,
    ) {
        val session = session(sessionId)
        val template = templates.byKey(session.templateKey) ?: throw ApiException(400, "模板不存在")
        val files = collectFiles(userId, scopeFileIds)
        val parsed = files.values.mapIndexed { index, row ->
            Triple(row, contentParser.parse(row.sha256, row.fileName, row.totalSize), index)
        }
        val extraction = builder.extract(template.key, parsed, aiEnabled)

        collabMapper.upsertContribution(
            sessionId, userId,
            Json.stringify(extraction.rows),
            extraction.usedFiles.sumOf { it.rows },
            (extraction.quality["suppressedGroups"] as? Int) ?: 0,
            1,
        )
        collabMapper.updateMemberProgress(
            sessionId, userId,
            extraction.rows.size,
            extraction.rows.size,
            MEMBER_CONTRIBUTED,
        )
        log.info("collab {} contribution {}: groups={} files={}", sessionId, userId, extraction.rows.size, files.size)
    }

    private fun memberAggregateOf(sessionId: Long, userId: Long): List<Map<String, Any?>> {
        val contribution = collabMapper.listContributions(sessionId).firstOrNull { it.userId == userId }
            ?: return emptyList()
        return (Json.asList(Json.parse(contribution.aggregateJson)) ?: emptyList()).mapNotNull { Json.asMap(it) }
    }

    /**
     * 合并所有成员的分组统计。
     * 这里采用**并集**：协同的目标是让产品随成员加入而增大，
     * 因此只要某一方出现过该分组就保留；不含任何原始行。
     */
    private fun mergedAggregate(session: CollabSession, members: List<CollabMember>): List<Map<String, String>> {
        val template = templates.byKey(session.templateKey) ?: return emptyList()
        val groupKey = template.groupBy.firstOrNull() ?: return emptyList()
        val accepted = members.filter { it.status == MEMBER_CONTRIBUTED }
        if (accepted.isEmpty()) return emptyList()

        val buckets = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        for (member in accepted) {
            for (row in memberAggregateOf(session.id ?: 0, member.userId)) {
                val key = Json.asString(row[groupKey]) ?: continue
                if (key.isBlank() || key == "未分类") continue
                buckets.getOrPut(key) { ArrayList() }.add(row)
            }
        }

        return buckets.map { (key, rows) ->
            val entry = linkedMapOf<String, String>()
            entry[groupKey] = key
            (template.measures + "sample_count").forEach { measure ->
                val values = rows.mapNotNull { Json.asString(it[measure])?.toDoubleOrNull() }
                if (values.isNotEmpty()) entry[measure] = "%.2f".format(values.average())
            }
            entry["sources"] = rows.size.toString()
            entry
        }.sortedByDescending { it["sample_count"]?.toDoubleOrNull() ?: 0.0 }
    }

    private fun mergeGroupCount(session: CollabSession, members: List<CollabMember>): Int =
        mergedAggregate(session, members).size

    private fun collectFiles(userId: Long, scopeFileIds: List<Long>): Map<Long, FileRow> {
        val files = LinkedHashMap<Long, FileRow>()
        for (scopeId in scopeFileIds.distinct()) {
            userFileMapper.expandRows(scopeId, userId).filter { it.isFolder == 0 }
                .forEach { row -> row.id?.let { files.putIfAbsent(it, row) } }
        }
        return files
    }

    private fun verify(code: String?, password: String?): CollabSession {
        val normalized = code?.trim()?.uppercase().orEmpty()
        if (normalized.isEmpty()) throw ApiException(400, "请输入协同号")
        if (password.isNullOrEmpty()) throw ApiException(400, "请输入协同密码")
        val session = collabMapper.findByCode(normalized) ?: throw ApiException(404, "协同号不存在")
        val hash = session.collabPasswordHash ?: throw ApiException(400, "该协同未设置密码")
        if (!passwordEncoder.matches(password, hash)) throw ApiException(403, "协同密码不正确")
        return session
    }

    private fun session(id: Long): CollabSession = collabMapper.findSession(id) ?: throw ApiException(404, "协同不存在")

    private fun requireMember(sessionId: Long, userId: Long) {
        collabMapper.findMember(sessionId, userId) ?: throw ApiException(403, "你还未加入该协同")
    }

    /** 协同号：8 位，数字+大写字母，去掉易混淆字符 */
    private fun generateCode(): String {
        repeat(20) {
            val code = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            if (collabMapper.findByCode(code) == null) return code
        }
        throw ApiException(500, "协同号生成失败，请重试")
    }

    /** 协同密码：6 位数字+字母 */
    private fun generatePassword(): String =
        (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    data class CreateRequest(
        val description: String? = null,
        val templateKey: String? = null,
        val title: String? = null,
        val message: String? = null,
        val scopeFileIds: List<Long> = emptyList(),
        val aiEnabled: Boolean? = null,
        val kThreshold: Int? = null,
    ) {
        val threshold: Int get() = kThreshold ?: 5
    }

    data class PeekRequest(val collabCode: String? = null, val collabPassword: String? = null)

    data class JoinRequest(
        val collabCode: String? = null,
        val collabPassword: String? = null,
        val scopeFileIds: List<Long> = emptyList(),
        val aiEnabled: Boolean? = null,
    )

    data class AddFilesRequest(
        val scopeFileIds: List<Long> = emptyList(),
        val aiEnabled: Boolean? = null,
    )

    data class PublishRequest(
        val title: String? = null,
        val summary: String? = null,
        val priceCents: Long? = null,
        val licenseType: String? = null,
    ) {
        val price: Long get() = priceCents ?: 0
    }

    data class CreateResult(
        val sessionId: Long,
        val collabCode: String,
        val collabPassword: String,
        val templateKey: String,
        val templateLabel: String,
        val title: String,
        val memberCount: Int,
        val detail: CollabDetail,
    )

    data class PeekResult(
        val sessionId: Long,
        val collabCode: String,
        val title: String,
        val description: String,
        val templateKey: String,
        val templateLabel: String,
        val templateDescription: String,
        val fields: List<String>,
        val ownerName: String,
        val memberCount: Int,
        val memberNames: List<String>,
        val state: Int,
        val stateLabel: String,
        val alreadyJoined: Boolean,
        val canJoin: Boolean,
    )

    /** 对外会话视图：不含协同密码哈希 */
    data class SessionView(
        val id: Long,
        val collabCode: String?,
        val templateKey: String,
        val templateLabel: String,
        val title: String,
        val description: String,
        val initiatorId: Long,
        val ownerId: Long,
        val partnerId: Long,
        val state: Int,
        val kThreshold: Int,
        val productId: Long?,
        val message: String?,
        val createdAt: String?,
        val updatedAt: String?,
        val initiatorName: String?,
        val partnerName: String?,
        val ownerName: String?,
    ) {
        companion object {
            fun of(session: CollabSession) = SessionView(
                id = session.id ?: 0,
                collabCode = session.collabCode,
                templateKey = session.templateKey,
                templateLabel = session.templateLabel,
                title = session.title,
                description = session.description,
                initiatorId = session.initiatorId,
                ownerId = session.ownerId,
                partnerId = session.partnerId,
                state = session.state,
                kThreshold = session.kThreshold,
                productId = session.productId,
                message = session.message,
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                initiatorName = session.initiatorName,
                partnerName = session.partnerName,
                ownerName = session.ownerName,
            )
        }
    }

    data class CollabDetail(
        val session: SessionView,
        val members: List<CollabMember>,
        val aggregate: List<Map<String, String>>,
        val totalRows: Int,
        val totalGroups: Int,
        val canPublish: Boolean,
        /** 当前用户是否为发起人（Kotlin 会把 isXxx 序列化成 xxx，这里用中性命名避免歧义） */
        val ownerViewer: Boolean,
    )

    companion object {
        const val STATE_ACTIVE = 0
        const val STATE_MERGED = 1
        const val STATE_CLOSED = 2

        const val ROLE_OWNER = 2
        const val ROLE_MEMBER = 1

        const val MEMBER_JOINED = 1
        const val MEMBER_CONTRIBUTED = 2

        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun stateLabel(state: Int): String = when (state) {
            0 -> "协同中"
            1 -> "已上架"
            else -> "已结束"
        }
    }

}
