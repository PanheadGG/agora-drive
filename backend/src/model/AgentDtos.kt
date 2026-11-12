package com.pgigi.agentclouddisk.model

data class BountyDto(
    val id: Long,
    val title: String,
    val rawDesc: String,
    val structuredJson: String,
    val status: Int,
    val statusLabel: String,
    val publisherId: Long,
    val publisherName: String,
    val createdAt: String?,
    val scanTaskCount: Long,
    val highMatchCount: Long,
    val mine: Boolean,
    val structuredBy: String = "rule",
    val structuredModel: String? = null,
)

data class BountyRequest(val title: String = "", val rawDesc: String = "")

/**
 * @param aiEnabled 用户是否打开「允许大模型判定」。false 时只做本地规则，
 *                  且任何结论都不得评为高匹配（最多"可能高匹配"）。
 */
data class ScanRequest(
    val bountyId: Long = 0,
    val scopeFileIds: List<Long> = emptyList(),
    /** 缺省（null）按"未开启"处理，避免旧前端/脚本未传字段时反序列化失败 */
    val aiEnabled: Boolean? = null,
) {
    val aiRequested: Boolean get() = aiEnabled == true
}

data class FolderNodeDto(val id: Long, val parentId: Long?, val name: String, val childCount: Long)

data class ScanResultDto(
    val id: Long,
    val userFileId: Long,
    val fileName: String,
    val fileSize: Long,
    val matchLevel: Int,
    val matchLabel: String,
    val parseStatus: Int,
    val parseMethod: String?,
    val parsedSummary: String?,
    val evidenceText: String?,
    val riskTag: String?,
    val riskDesc: String?,
    val userReview: Int,
    val reviewNote: String?,
    val judgeSource: String = "rule",
    val judgeReason: String? = null,
    val previewKind: String? = null,
    /** 是否因为「未开启大模型判定」而被限制过等级 */
    val levelCapped: Boolean = false,
    /** 图片视觉模型对该图片画面的结论（仅图片类文件有值） */
    val visionScene: String? = null,
    val visionOk: Boolean? = null,
)

data class ScanTaskDetailDto(
    val id: Long,
    val bountyId: Long,
    val bountyTitle: String,
    val taskStatus: Int,
    val statusLabel: String,
    val totalFileCount: Int,
    val successParseCount: Int,
    val failParseCount: Int,
    val createdAt: String?,
    val finishedAt: String?,
    val scopeNames: List<String>,
    val authorityNote: String,
    val understanding: String,
    val excludeKeywords: List<String>,
    val skipped: List<ScanResultDto>,
    val parsedList: List<ScanResultDto>,
    val recommendations: List<ScanResultDto>,
    val highRisk: List<ScanResultDto>,
    val reviewTips: List<String>,
    val llmModel: String? = null,
    val llmUsed: Boolean = false,
    val llmJudgedCount: Int = 0,
    val aiEnabled: Boolean = false,
    val llmAvailable: Boolean = false,
    val visionUsedCount: Int = 0,
    val possibleHighCount: Int = 0,
    val notices: List<String> = emptyList(),
)

data class ScanTaskRowDto(
    val id: Long,
    val bountyId: Long,
    val bountyTitle: String?,
    val userName: String?,
    val taskStatus: Int,
    val statusLabel: String,
    val totalFileCount: Int,
    val successParseCount: Int,
    val failParseCount: Int,
    val createdAt: String?,
    val finishedAt: String?,
)

data class ReviewRequest(val review: Int = 0, val note: String = "")

data class ScanStartDto(val taskId: Long, val taskStatus: Int, val statusLabel: String, val totalFileCount: Int)

data class ScanEventDto(
    val seq: Int,
    val fileId: Long,
    val fileName: String,
    val stage: String,
    val parseMethod: String?,
    val ok: Boolean,
    val riskTag: String?,
    val detail: String?,
    val at: Long,
)

data class ScanProgressDto(
    val taskId: Long,
    val taskStatus: Int,
    val statusLabel: String,
    val total: Int,
    val processed: Int,
    val success: Int,
    val fail: Int,
    val percent: Int,
    val currentFile: String?,
    val stage: String,
    val elapsedMs: Long,
    val done: Boolean,
    val message: String?,
    val events: List<ScanEventDto>,
)

data class ScanPreviewDto(
    val resultId: Long,
    val fileId: Long,
    val fileName: String,
    val kind: String,
    val parseMethod: String?,
    val text: String,
    val header: List<String>,
    val rows: List<List<String>>,
    val riskTag: String?,
    val riskDesc: String?,
    val truncated: Boolean,
)
