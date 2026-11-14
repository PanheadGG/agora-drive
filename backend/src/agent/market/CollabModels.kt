package com.pgigi.agentclouddisk.agent.market

/** 协同会话（按「协同号 + 密码」识别，支持多成员） */
data class CollabSession(
    var id: Long? = null,
    var collabCode: String? = null,
    var collabPasswordHash: String? = null,
    var templateKey: String = "",
    var templateLabel: String = "",
    var title: String = "",
    var description: String = "",
    var initiatorId: Long = 0,
    var ownerId: Long = 0,
    var partnerId: Long = 0,
    var state: Int = 0,
    var kThreshold: Int = 5,
    var productId: Long? = null,
    var message: String? = null,
    var createdAt: String? = null,
    var updatedAt: String? = null,
    var initiatorName: String? = null,
    var partnerName: String? = null,
    var ownerName: String? = null,
)

/** 协同成员：谁参与了、用了哪些文件、贡献了多少 */
data class CollabMember(
    var id: Long? = null,
    var sessionId: Long = 0,
    var userId: Long = 0,
    var role: Int = 1,
    var scopeJson: String = "[]",
    var fileCount: Int = 0,
    var rowsContributed: Int = 0,
    var groupsContributed: Int = 0,
    var status: Int = 0,
    var joinedAt: String? = null,
    var userName: String? = null,
)
