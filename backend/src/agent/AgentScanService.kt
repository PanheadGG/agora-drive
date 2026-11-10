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
import com.pgigi.agentclouddisk.model.ScanEventDto
import com.pgigi.agentclouddisk.model.ScanPreviewDto
import com.pgigi.agentclouddisk.model.ScanProgressDto
import com.pgigi.agentclouddisk.model.ScanRequest
import com.pgigi.agentclouddisk.model.ScanResult
import com.pgigi.agentclouddisk.model.ScanResultDto
import com.pgigi.agentclouddisk.model.ScanStartDto
import com.pgigi.agentclouddisk.model.ScanTaskDetailDto
import com.pgigi.agentclouddisk.model.ScanTaskRowDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ExecutorService

/**
 * Agent 扫描链路（异步 + 可观测）：
 * 授权范围 → 逐文件流式解析与敏感检测（含 EasyExcel 表格流式、docx 流式）→ 本机敏感信息兜底
 * → DeepSeek 语义判定 → 证据化推荐 → 审计留痕。
 *
 * 接口立即返回 taskId，扫描在独立线程池里跑，前端轮询进度接口实时看到"正在解析哪个文件、走到哪一步"。
 */
@Service
class AgentScanService(
    private val userFileMapper: UserFileMapper,
    private val bountyMapper: BountyMapper,
    private val scanTaskMapper: ScanTaskMapper,
    private val scanResultMapper: ScanResultMapper,
    private val contentParser: FileContentParser,
    private val matchJudge: LlmMatchJudge,
    private val llmClient: LlmClient,
    private val ocrService: OcrService,
    private val progressRegistry: ScanProgressRegistry,
    private val scanExecutor: ExecutorService,
) {

    private val log = LoggerFactory.getLogger(AgentScanService::class.java)

    fun folders(userId: Long): List<FolderNodeDto> =
        userFileMapper.listFolders(userId).map { FolderNodeDto(it.id ?: 0, it.parentId, it.fileName, it.childCount) }

    fun myTasks(userId: Long): List<ScanTaskRowDto> = scanTaskMapper.listByUser(userId).map { it.toRowDto() }

    fun auditTasks(bountyId: Long?): List<ScanTaskRowDto> = scanTaskMapper.listAll(bountyId).map { it.toRowDto() }

    fun llmStatus(): Map<String, Any?> = linkedMapOf(
        "llm" to llmClient.status(),
        "ocr" to ocrService.status(),
    )

    /** 只做校验与建任务，真正的扫描丢到线程池，接口立刻返回 */
    fun startScan(userId: Long, req: ScanRequest): ScanStartDto {
        if (req.scopeFileIds.isEmpty()) throw ApiException(400, "请先选择授权扫描的文件夹或文件")
        val bounty = bountyMapper.findById(req.bountyId) ?: throw ApiException(404, "悬赏不存在")
        if (bounty.status != 1) throw ApiException(400, "该悬赏已停用，无法发起扫描")

        val requirement = BountyStructureParser.fromJson(bounty.structuredJson)
            ?: BountyStructureParser.parse(bounty.title + " " + bounty.rawDesc)

        val files = LinkedHashMap<Long, FileRow>()
        for (scopeId in req.scopeFileIds.distinct()) {
            val rows = userFileMapper.expandRows(scopeId, userId)
            if (rows.isEmpty()) throw ApiException(400, "授权范围包含不存在或不属于本人的条目：" + scopeId)
            rows.filter { it.isFolder == 0 }.forEach { row -> row.id?.let { files.putIfAbsent(it, row) } }
        }

        val scopeJson = req.scopeFileIds.distinct().joinToString(",", "[", "]")
        val taskId = scanTaskMapper.insert(req.bountyId, userId, scopeJson)
        val progress = progressRegistry.create(taskId, files.size)
        progress.stage = "排队中"

        scanExecutor.submit { runScan(taskId, requirement, files.values.toList(), progress) }
        log.info("scan task {} submitted, files={}", taskId, files.size)
        return ScanStartDto(taskId, 1, "扫描中", files.size)
    }

    private fun runScan(
        taskId: Long,
        requirement: BountyStructureParser.Requirement,
        files: List<FileRow>,
        progress: ScanProgressRegistry.Progress,
    ) {
        try {
            progress.status = 1
            scanTaskMapper.updateProgress(taskId, 1, files.size, 0, 0)

            data class Pending(
                val row: FileRow,
                val parsed: FileContentParser.ParsedFile,
            )

            val pendings = ArrayList<Pending>(files.size)
            var index = 0
            for (row in files) {
                index++
                progress.currentFile = row.fileName
                progress.stage = "解析内容（" + index + "/" + files.size + "）"
                val parsed = contentParser.parse(row.sha256, row.fileName, row.totalSize)
                pendings += Pending(row, parsed)

                val riskTag = parsed.risk?.tag ?: if (parsed.copied) "网络搬运" else null
                if (parsed.ok) progress.success++ else progress.fail++
                progress.processed = index
                progress.addEvent(
                    fileId = row.id ?: 0,
                    fileName = row.fileName,
                    stage = if (parsed.ok) "解析完成" else "跳过",
                    parseMethod = parsed.method,
                    ok = parsed.ok,
                    riskTag = riskTag,
                    detail = if (parsed.ok) parsed.summary.take(80) else parsed.note,
                )
            }

            val candidates = pendings.filter { it.parsed.ok && it.parsed.risk == null && !it.parsed.copied }
            val llmApplied = matchJudge.available && candidates.isNotEmpty()
            if (llmApplied) {
                progress.stage = "语义判定（" + candidates.size + " 个文件交给大模型）"
                progress.currentFile = null
            }
            val llmVerdicts = if (llmApplied) {
                matchJudge.judge(
                    requirement,
                    candidates.map { pending ->
                        LlmMatchJudge.Item(
                            fileId = pending.row.id ?: 0,
                            fileName = pending.row.fileName,
                            parseMethod = pending.parsed.method,
                            text = pending.parsed.text,
                        )
                    },
                )
            } else {
                emptyMap()
            }

            progress.stage = "写入结果"
            var success = 0
            var fail = 0
            for (pending in pendings) {
                val fileId = pending.row.id ?: 0
                val verdict = resolveVerdict(pending.parsed, requirement, llmVerdicts[fileId])
                scanResultMapper.insert(
                    taskId,
                    fileId,
                    verdict.level,
                    if (pending.parsed.ok) 0 else 1,
                    pending.parsed.method,
                    pending.parsed.summary.ifEmpty { pending.parsed.note ?: "" },
                    verdict.evidence,
                    verdict.riskTag,
                    verdict.riskDesc,
                    verdict.source,
                    verdict.reason,
                    pending.parsed.previewText.ifEmpty { pending.parsed.note ?: "" },
                    pending.parsed.previewKind,
                )
                if (pending.parsed.ok) success++ else fail++
            }

            scanTaskMapper.updateProgress(taskId, 2, files.size, success, fail)
            progress.processed = files.size
            progress.success = success
            progress.fail = fail
            progress.finish(2)
            log.info("scan task {} done: files={} parsed={} failed={} llmJudged={}", taskId, files.size, success, fail, llmVerdicts.size)
        } catch (error: Exception) {
            log.error("scan task {} failed", taskId, error)
            runCatching { scanTaskMapper.updateProgress(taskId, 3, files.size, progress.success, progress.fail) }
            progress.finish(3, error.message ?: error.javaClass.simpleName)
        }
    }

    private data class Verdict(
        val level: Int,
        val evidence: String?,
        val riskTag: String?,
        val riskDesc: String?,
        val source: String,
        val reason: String?,
    )

    private fun resolveVerdict(
        parsed: FileContentParser.ParsedFile,
        requirement: BountyStructureParser.Requirement,
        llmVerdict: LlmMatchJudge.Judgement?,
    ): Verdict {
        if (!parsed.ok) return Verdict(0, null, null, null, "rule", parsed.note)

        val localHits = requirement.matchKeywords.filter { parsed.text.contains(it) }
        val localEvidence = snippet(parsed.text, localHits.firstOrNull())

        parsed.risk?.let { risk ->
            return Verdict(
                level = 0,
                evidence = localEvidence,
                riskTag = risk.tag,
                riskDesc = risk.desc + "（本机流式检测命中，正文未外发；高风险，不建议直接提交）",
                source = "rule",
                reason = "本机敏感信息检测命中，未送模型判定",
            )
        }
        if (parsed.copied) {
            return Verdict(
                level = 0,
                evidence = localEvidence,
                riskTag = "网络搬运",
                riskDesc = "解析到搬运特征：" + parsed.copiedMarkers.joinToString("、") + "，按规则排除",
                source = "rule",
                reason = "本机搬运特征检测命中",
            )
        }
        if (llmVerdict != null) {
            val evidence = llmVerdict.evidence?.takeIf { it.isNotBlank() && isGrounded(it, parsed.text) } ?: localEvidence
            return Verdict(
                level = llmVerdict.matchLevel,
                evidence = evidence,
                riskTag = llmVerdict.riskTag,
                riskDesc = llmVerdict.riskDesc,
                source = "llm",
                reason = llmVerdict.reason,
            )
        }

        val level = when {
            localHits.size >= 3 -> 3
            localHits.size == 2 -> 2
            localHits.size == 1 -> 1
            else -> 0
        }
        return Verdict(
            level = level,
            evidence = localEvidence,
            riskTag = null,
            riskDesc = null,
            source = "rule",
            reason = if (localHits.isEmpty()) "未命中需求关键词" else "命中关键词：" + localHits.joinToString("、"),
        )
    }

    /** 证据必须能在解析正文里找到，防止模型编造 */
    private fun isGrounded(evidence: String, text: String): Boolean {
        val normalizedEvidence = evidence.replace(Regex("""\s+"""), "")
        val normalizedText = text.replace(Regex("""\s+"""), "")
        if (normalizedEvidence.length < 4) return false
        return normalizedText.contains(normalizedEvidence) ||
            normalizedEvidence.take(12).let { normalizedText.contains(it) }
    }

    private fun snippet(text: String, keyword: String?): String? {
        val flattened = text.replace(Regex("""\s+"""), " ").trim()
        if (keyword == null) return flattened.take(120).ifEmpty { null }
        val index = text.indexOf(keyword)
        if (index < 0) return flattened.take(120).ifEmpty { null }
        val start = (index - 40).coerceAtLeast(0)
        val end = (index + keyword.length + 80).coerceAtMost(text.length)
        return text.substring(start, end).replace(Regex("""\s+"""), " ").trim()
    }

    // ---------- 进度 ----------

    fun progress(taskId: Long, userId: Long, role: Int): ScanProgressDto {
        val task = scanTaskMapper.findById(taskId) ?: throw ApiException(404, "扫描任务不存在")
        if (role != Roles.ADMIN && task.userId != userId) throw ApiException(403, "无权查看该扫描任务")

        val live = progressRegistry.get(taskId)
        val status = live?.status ?: task.taskStatus
        val total = live?.total ?: task.totalFileCount
        val processed = live?.processed ?: if (task.taskStatus >= 2) task.totalFileCount else 0
        val success = live?.success ?: task.successParseCount
        val fail = live?.fail ?: task.failParseCount
        val startedAt = live?.startedAt ?: System.currentTimeMillis()
        val finishedAt = live?.finishedAt
        val elapsed = if (finishedAt != null) finishedAt - startedAt else (System.currentTimeMillis() - startedAt)

        return ScanProgressDto(
            taskId = taskId,
            taskStatus = status,
            statusLabel = statusLabel(status),
            total = total,
            processed = processed,
            success = success,
            fail = fail,
            percent = if (total == 0) 0 else (processed * 100 / total).coerceIn(0, 100),
            currentFile = live?.currentFile,
            stage = live?.stage ?: statusLabel(status),
            elapsedMs = elapsed,
            done = status == 2 || status == 3,
            message = live?.message,
            events = live?.events?.map {
                ScanEventDto(it.seq, it.fileId, it.fileName, it.stage, it.parseMethod, it.ok, it.riskTag, it.detail, it.at)
            } ?: emptyList(),
        )
    }

    // ---------- 预览 ----------

    fun preview(userId: Long, role: Int, resultId: Long): ScanPreviewDto {
        val result = scanResultMapper.findById(resultId) ?: throw ApiException(404, "匹配结果不存在")
        val task = scanTaskMapper.findById(result.scanTaskId) ?: throw ApiException(404, "扫描任务不存在")
        if (role != Roles.ADMIN && task.userId != userId) throw ApiException(403, "只能查看自己的扫描结果")

        val row = userFileMapper.findRow(result.userFileId, task.userId)
            ?: throw ApiException(404, "文件已删除，无法预览")
        val fileName = result.fileName ?: row.fileName
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isImage = ext in IMAGE_EXTS

        val kind = when {
            isImage -> "image"
            result.previewKind == "table" -> "table"
            else -> "text"
        }
        val text = result.previewText.orEmpty()
        val tableLines = if (kind == "table") text.lineSequence().filter { it.isNotBlank() }.toList() else emptyList()
        val header = tableLines.firstOrNull()?.split(" | ")?.map { it.trim() } ?: emptyList()
        val rows = tableLines.drop(1).map { line ->
            val cells = line.split(" | ").map { it.trim() }
            if (cells.size >= header.size) cells else cells + List(header.size - cells.size) { "" }
        }

        return ScanPreviewDto(
            resultId = resultId,
            fileId = result.userFileId,
            fileName = fileName,
            kind = kind,
            parseMethod = result.parseMethod,
            text = text,
            header = header,
            rows = rows,
            riskTag = result.riskTag,
            riskDesc = result.riskDesc,
            truncated = text.length >= 6000,
        )
    }

    // ---------- 报告 ----------

    fun detail(taskId: Long, userId: Long, role: Int): ScanTaskDetailDto {
        val task = scanTaskMapper.findById(taskId) ?: throw ApiException(404, "扫描任务不存在")
        if (role != Roles.ADMIN && task.userId != userId) throw ApiException(403, "无权查看该扫描任务")

        val bounty = bountyMapper.findById(task.bountyId) ?: throw ApiException(404, "悬赏不存在")
        val requirement = BountyStructureParser.fromJson(bounty.structuredJson)
            ?: BountyStructureParser.parse(bounty.title + " " + bounty.rawDesc)
        val results = scanResultMapper.listByTask(taskId).map { it.toDto() }

        val scopeIds = Regex("""\d+""").findAll(task.authScopeJson).map { it.value.toLong() }.toList()
        val scopeNames = scopeIds.map { scopeId ->
            userFileMapper.expandRows(scopeId, task.userId).firstOrNull { it.id == scopeId }?.fileName ?: ("#" + scopeId)
        }

        val parsedList = results.filter { it.parseStatus == 0 }
        val skipped = results.filter { it.parseStatus == 1 }
        val recommendations = parsedList.filter { it.matchLevel > 0 }.sortedByDescending { it.matchLevel }
        val highRisk = parsedList.filter { !it.riskTag.isNullOrBlank() }
        val llmJudged = results.count { it.judgeSource == "llm" }

        val tips = buildList {
            if (recommendations.any { it.matchLevel == 3 }) {
                add("重点复核高匹配条目：" + recommendations.filter { it.matchLevel == 3 }.joinToString("、") { it.fileName })
            }
            if (highRisk.isNotEmpty()) {
                add("以下文件解析出敏感/搬运特征，建议先脱敏或直接放弃：" + highRisk.joinToString("、") { it.fileName })
            }
            if (skipped.isNotEmpty()) {
                add("本次未能解析：" + skipped.joinToString("、") { it.fileName } + "，如需纳入请人工确认该格式的解析能力")
            }
            if (recommendations.isEmpty()) add("本次没有推荐条目，可扩大授权范围或换一条悬赏再试")
            if (llmJudged > 0) {
                add("本次有 " + llmJudged + " 个文件由大模型语义判定，证据已校验必须出现在解析正文中")
            } else {
                add("本次判定使用本地规则（未配置可用模型平台或调用失败），配置后可获得更好的语义判断")
            }
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
            llmModel = llmClient.activeModel.takeIf { llmClient.available },
            llmUsed = llmJudged > 0,
            llmJudgedCount = llmJudged,
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
        judgeSource = judgeSource,
        judgeReason = judgeReason,
        previewKind = previewKind,
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
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        fun matchLabel(level: Int): String = when (level) {
            3 -> "高匹配"
            2 -> "中匹配"
            1 -> "低匹配"
            else -> "不推荐"
        }
    }
}
