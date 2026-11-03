package com.pgigi.agentclouddisk.agent

import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.common.Roles
import com.pgigi.agentclouddisk.mapper.BountyMapper
import com.pgigi.agentclouddisk.mapper.ScanResultMapper
import com.pgigi.agentclouddisk.mapper.ScanTaskMapper
import com.pgigi.agentclouddisk.mapper.UserFileMapper
import com.pgigi.agentclouddisk.model.FileRow
import com.pgigi.agentclouddisk.model.FolderNodeDto
import com.pgigi.agentclouddisk.model.ReviewRequest
import com.pgigi.agentclouddisk.model.ScanRequest
import com.pgigi.agentclouddisk.model.ScanResult
import com.pgigi.agentclouddisk.model.ScanResultDto
import com.pgigi.agentclouddisk.model.ScanTaskDetailDto
import com.pgigi.agentclouddisk.model.ScanTaskRowDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Agent 扫描链路：授权范围 → 逐文件真实内容解析 → 敏感/搬运检测 → 语义匹配分级 → 证据化推荐 → 审计留痕。
 * Demo 同步执行一次扫描（授权范围通常只有几十个文件），任务状态仍按 排队/扫描中/完成 记录。
 */
@Service
class AgentScanService(
    private val userFileMapper: UserFileMapper,
    private val bountyMapper: BountyMapper,
    private val scanTaskMapper: ScanTaskMapper,
    private val scanResultMapper: ScanResultMapper,
    private val contentParser: FileContentParser,
) {

    fun folders(userId: Long): List<FolderNodeDto> =
        userFileMapper.listFolders(userId).map { FolderNodeDto(it.id ?: 0, it.parentId, it.fileName, it.childCount) }

    fun myTasks(userId: Long): List<ScanTaskRowDto> =
        scanTaskMapper.listByUser(userId).map { it.toRowDto() }

    fun auditTasks(bountyId: Long?): List<ScanTaskRowDto> =
        scanTaskMapper.listAll(bountyId).map { it.toRowDto() }

    @Transactional
    fun startScan(userId: Long, req: ScanRequest): ScanTaskDetailDto {
        if (req.scopeFileIds.isEmpty()) throw ApiException(400, "请先选择授权扫描的文件夹或文件")
        val bounty = bountyMapper.findById(req.bountyId) ?: throw ApiException(404, "悬赏不存在")
        if (bounty.status != 1) throw ApiException(400, "该悬赏已停用，无法发起扫描")

        val requirement = BountyStructureParser.parse(bounty.title + " " + bounty.rawDesc)

        val files = LinkedHashMap<Long, FileRow>()
        for (scopeId in req.scopeFileIds.distinct()) {
            val rows = userFileMapper.expandRows(scopeId, userId)
            if (rows.isEmpty()) throw ApiException(400, "授权范围包含不存在或不属于本人的条目：" + scopeId)
            rows.filter { it.isFolder == 0 }.forEach { row -> row.id?.let { files.putIfAbsent(it, row) } }
        }

        val scopeJson = req.scopeFileIds.distinct().joinToString(",", "[", "]")
        val taskId = scanTaskMapper.insert(req.bountyId, userId, scopeJson)

        var success = 0
        var fail = 0
        for (row in files.values) {
            val parsed = contentParser.parse(row.sha256, row.fileName, row.totalSize)
            val risk = if (parsed.ok) RiskDetector.detect(parsed.text) else null
            val copied = parsed.ok && RiskDetector.isWebCopied(parsed.text)
            val hits = if (parsed.ok) requirement.matchKeywords.filter { parsed.text.contains(it) } else emptyList()

            val level = when {
                !parsed.ok || copied || risk != null -> 0
                hits.size >= 3 -> 3
                hits.size == 2 -> 2
                hits.size == 1 -> 1
                else -> 0
            }
            val riskTag = when {
                !parsed.ok -> null
                risk != null -> risk.tag
                copied -> "网络搬运"
                else -> null
            }
            val riskDesc = when {
                !parsed.ok -> null
                risk != null -> risk.desc + "（高风险，不建议直接提交）"
                copied -> "解析到搬运特征：" + RiskDetector.copiedMarkers(parsed.text).joinToString("、") + "，按规则排除"
                else -> null
            }

            scanResultMapper.insert(
                taskId,
                row.id ?: 0,
                level,
                if (parsed.ok) 0 else 1,
                parsed.method,
                parsed.summary.ifEmpty { parsed.note ?: "" },
                evidence(parsed.text, hits),
                riskTag,
                riskDesc,
            )
            if (parsed.ok) success++ else fail++
        }

        scanTaskMapper.updateProgress(taskId, 2, files.size, success, fail)
        return detail(taskId, userId, Roles.USER)
    }

    fun detail(taskId: Long, userId: Long, role: Int): ScanTaskDetailDto {
        val task = scanTaskMapper.findById(taskId) ?: throw ApiException(404, "扫描任务不存在")
        if (role != Roles.ADMIN && task.userId != userId) throw ApiException(403, "无权查看该扫描任务")

        val bounty = bountyMapper.findById(task.bountyId) ?: throw ApiException(404, "悬赏不存在")
        val requirement = BountyStructureParser.parse(bounty.title + " " + bounty.rawDesc)
        val results = scanResultMapper.listByTask(taskId).map { it.toDto() }

        val scopeIds = Regex("""\d+""").findAll(task.authScopeJson).map { it.value.toLong() }.toList()
        val scopeNames = scopeIds.map { scopeId ->
            userFileMapper.expandRows(scopeId, task.userId).firstOrNull { it.id == scopeId }?.fileName ?: ("#" + scopeId)
        }

        val parsedList = results.filter { it.parseStatus == 0 }
        val skipped = results.filter { it.parseStatus == 1 }
        val recommendations = parsedList.filter { it.matchLevel > 0 }.sortedByDescending { it.matchLevel }
        val highRisk = parsedList.filter { !it.riskTag.isNullOrBlank() }

        val tips = buildList {
            if (recommendations.any { it.matchLevel == 3 }) add("重点复核高匹配条目：" + recommendations.filter { it.matchLevel == 3 }.joinToString("、") { it.fileName })
            if (highRisk.isNotEmpty()) add("以下文件解析出敏感/搬运特征，建议先脱敏或直接放弃：" + highRisk.joinToString("、") { it.fileName })
            if (skipped.isNotEmpty()) add("本次未能解析（图片/PDF/压缩包等）：" + skipped.joinToString("、") { it.fileName } + "，如需纳入请人工开启对应解析能力")
            if (recommendations.isEmpty()) add("本次没有推荐条目，可扩大授权范围或换一条悬赏再试")
            add("Agent 只做推荐，不会自动提交或对外分发；确认可用后请自行脱敏再交付")
        }

        return ScanTaskDetailDto(
            id = taskId,
            bountyId = task.bountyId,
            bountyTitle = task.bountyTitle ?: bounty.title,
            taskStatus = task.taskStatus,
            statusLabel = statusLabel(task.taskStatus),
            totalFileCount = task.totalFileCount,
            successParseCount = task.successParseCount,
            failParseCount = task.failParseCount,
            createdAt = task.createdAt,
            finishedAt = task.finishedAt,
            scopeNames = scopeNames,
            authorityNote = "仅在授权范围内扫描：" + scopeNames.joinToString("、") + "，其余文件未访问",
            understanding = requirement.understanding,
            excludeKeywords = requirement.excludeKeywords,
            skipped = skipped,
            parsedList = parsedList,
            recommendations = recommendations,
            highRisk = highRisk,
            reviewTips = tips,
        )
    }

    fun review(userId: Long, resultId: Long, req: ReviewRequest): ScanResultDto {
        if (req.review !in 0..2) throw ApiException(400, "复核状态非法")
        val result = scanResultMapper.findById(resultId) ?: throw ApiException(404, "匹配结果不存在")
        val task = scanTaskMapper.findById(result.scanTaskId) ?: throw ApiException(404, "扫描任务不存在")
        if (task.userId != userId) throw ApiException(403, "只能复核自己的匹配结果")
        scanResultMapper.updateReview(resultId, req.review, req.note.trim().ifEmpty { null })
        return (scanResultMapper.findById(resultId) ?: result).toDto()
    }

    private fun evidence(text: String, hits: List<String>): String? {
        val hit = hits.firstOrNull() ?: return null
        val index = text.indexOf(hit)
        if (index < 0) return null
        val start = (index - 40).coerceAtLeast(0)
        val end = (index + hit.length + 80).coerceAtMost(text.length)
        return text.substring(start, end).replace(Regex("""\s+"""), " ").trim()
    }

    private fun statusLabel(status: Int): String = when (status) {
        0 -> "排队中"
        1 -> "扫描中"
        2 -> "已完成"
        else -> "失败"
    }

    private fun ScanResult.toDto() = ScanResultDto(
        id = id ?: 0,
        userFileId = userFileId,
        fileName = fileName ?: "",
        fileSize = fileSize,
        matchLevel = matchLevel,
        matchLabel = matchLabel(matchLevel),
        parseStatus = parseStatus,
        parseMethod = parseMethod,
        parsedSummary = parsedSummary,
        evidenceText = evidenceText,
        riskTag = riskTag,
        riskDesc = riskDesc,
        userReview = userReview,
        reviewNote = reviewNote,
    )

    private fun com.pgigi.agentclouddisk.model.ScanTask.toRowDto() = ScanTaskRowDto(
        id = id ?: 0,
        bountyId = bountyId,
        bountyTitle = bountyTitle,
        userName = userName,
        taskStatus = taskStatus,
        statusLabel = statusLabel(taskStatus),
        totalFileCount = totalFileCount,
        successParseCount = successParseCount,
        failParseCount = failParseCount,
        createdAt = createdAt,
        finishedAt = finishedAt,
    )

    companion object {
        fun matchLabel(level: Int): String = when (level) {
            3 -> "高匹配"
            2 -> "中匹配"
            1 -> "低匹配"
            else -> "不推荐"
        }
    }
}
