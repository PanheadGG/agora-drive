package com.pgigi.agentclouddisk.agent.market

/** 数据产品（Agent 产出物） */
data class DataProduct(
    var id: Long? = null,
    var ownerId: Long = 0,
    var title: String = "",
    var templateKey: String = "",
    var templateLabel: String = "",
    var schemaJson: String = "[]",
    var rowCount: Long = 0,
    var contributingFiles: Int = 0,
    var coverageStart: String? = null,
    var coverageEnd: String? = null,
    var qualityJson: String = "{}",
    var tagsJson: String = "[]",
    var sampleJson: String = "[]",
    var buildReceipt: String = "{}",
    var piiStatus: String = "clean",
    var status: Int = 0,
    var sourceKind: String = "solo",
    var aiEnabled: Int = 0,
    var createdAt: String? = null,
    var updatedAt: String? = null,
    var ownerName: String? = null,
)

data class ProductBuildTask(
    var id: Long? = null,
    var productId: Long = 0,
    var userId: Long = 0,
    var scopeJson: String = "[]",
    var taskStatus: Int = 0,
    var totalFileCount: Int = 0,
    var successParseCount: Int = 0,
    var failParseCount: Int = 0,
    var aiEnabled: Int = 0,
    var modelName: String? = null,
    var createdAt: String? = null,
    var finishedAt: String? = null,
)

data class ProductBuildItem(
    var id: Long? = null,
    var taskId: Long = 0,
    var userFileId: Long = 0,
    var fileSha256: String? = null,
    var fileName: String? = null,
    var parseMethod: String? = null,
    var rowsContributed: Int = 0,
    var status: Int = 0,
    var riskTag: String? = null,
    var note: String? = null,
    var createdAt: String? = null,
)

data class ProductListing(
    var id: Long? = null,
    var productId: Long = 0,
    var sellerId: Long = 0,
    var title: String = "",
    var summary: String = "",
    var fieldList: String = "[]",
    var tagsJson: String = "[]",
    var domain: String = "",
    var coverageStart: String? = null,
    var coverageEnd: String? = null,
    var rowCount: Long = 0,
    var qualityScore: Int = 0,
    var priceCents: Long = 0,
    var licenseType: String = "internal",
    var status: Int = 1,
    var listedAt: String? = null,
    var delistedAt: String? = null,
    var sellerName: String? = null,
)

data class MarketDemand(
    var id: Long? = null,
    var buyerId: Long = 0,
    var title: String = "",
    var rawDesc: String = "",
    var structuredJson: String = "{}",
    var budgetCents: Long = 0,
    var status: Int = 1,
    var createdAt: String? = null,
    var buyerName: String? = null,
)

data class MarketMatch(
    var id: Long? = null,
    var demandId: Long = 0,
    var listingId: Long = 0,
    var score: Int = 0,
    var reasonsJson: String = "[]",
    var state: Int = 0,
    var pushedAt: String? = null,
    var readAt: String? = null,
    var note: String? = null,
    var listingTitle: String? = null,
    var listingSummary: String? = null,
    var listingPriceCents: Long = 0,
    var sellerName: String? = null,
    var demandTitle: String? = null,
)

// CollabSession 已迁移到 CollabModels.kt（增加协同号/密码/多成员字段）

data class CollabContribution(
    var id: Long? = null,
    var sessionId: Long = 0,
    var userId: Long = 0,
    var aggregateJson: String = "[]",
    var groupCount: Int = 0,
    var suppressedGroups: Int = 0,
    var approved: Int = 0,
    var createdAt: String? = null,
    var userName: String? = null,
)

data class DeliveryRequest(
    var id: Long? = null,
    var matchId: Long = 0,
    var buyerId: Long = 0,
    var sellerId: Long = 0,
    var status: Int = 0,
    var note: String? = null,
    var createdAt: String? = null,
    var updatedAt: String? = null,
    var listingTitle: String? = null,
    var buyerName: String? = null,
    var sellerName: String? = null,
)
