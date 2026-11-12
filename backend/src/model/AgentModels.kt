package com.pgigi.agentclouddisk.model

data class Bounty(
    var id: Long? = null,
    var publisherUserId: Long = 0,
    var title: String = "",
    var rawDesc: String = "",
    var structuredJson: String = "{}",
    var status: Int = 1,
    var createdAt: String? = null,
    var publisherName: String = "",
    var structuredBy: String = "rule",
    var structuredModel: String? = null,
)

data class ScanTask(
    var id: Long? = null,
    var bountyId: Long = 0,
    var userId: Long = 0,
    var authScopeJson: String = "[]",
    var taskStatus: Int = 0,
    var totalFileCount: Int = 0,
    var successParseCount: Int = 0,
    var failParseCount: Int = 0,
    var createdAt: String? = null,
    var finishedAt: String? = null,
    var bountyTitle: String? = null,
    var userName: String? = null,
    var aiEnabled: Int = 0,
)

data class ScanResult(
    var id: Long? = null,
    var scanTaskId: Long = 0,
    var userFileId: Long = 0,
    var matchLevel: Int = 0,
    var parseStatus: Int = 0,
    var parseMethod: String? = null,
    var parsedSummary: String? = null,
    var evidenceText: String? = null,
    var riskTag: String? = null,
    var riskDesc: String? = null,
    var userReview: Int = 0,
    var reviewNote: String? = null,
    var createdAt: String? = null,
    var fileName: String? = null,
    var fileSize: Long = 0,
    var judgeSource: String = "rule",
    var judgeReason: String? = null,
    var previewText: String? = null,
    var previewKind: String? = null,
    var visionScene: String? = null,
    var visionOk: Int? = null,
    var levelCapped: Int = 0,
)

data class FolderRow(
    var id: Long? = null,
    var parentId: Long? = null,
    var fileName: String = "",
    var childCount: Long = 0,
)
