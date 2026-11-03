package com.pgigi.agentclouddisk.agent

import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.common.Roles
import com.pgigi.agentclouddisk.mapper.BountyMapper
import com.pgigi.agentclouddisk.mapper.ScanResultMapper
import com.pgigi.agentclouddisk.mapper.ScanTaskMapper
import com.pgigi.agentclouddisk.model.Bounty
import com.pgigi.agentclouddisk.model.BountyDto
import com.pgigi.agentclouddisk.model.BountyRequest
import org.springframework.stereotype.Service

@Service
class BountyService(
    private val bountyMapper: BountyMapper,
    private val scanTaskMapper: ScanTaskMapper,
    private val scanResultMapper: ScanResultMapper,
) {

    fun list(userId: Long, role: Int, status: Int? = null): List<BountyDto> {
        val rows = when {
            role == Roles.ADMIN -> bountyMapper.list(status)
            role == Roles.ENTERPRISE -> bountyMapper.listByPublisher(userId)
            else -> bountyMapper.list(status ?: 1)
        }
        return rows.map { toDto(it, userId) }
    }

    fun create(userId: Long, role: Int, req: BountyRequest): BountyDto {
        if (role != Roles.ENTERPRISE) throw ApiException(403, "只有企业需求用户可以发布悬赏")
        val title = req.title.trim()
        val rawDesc = req.rawDesc.trim()
        if (title.isEmpty()) throw ApiException(400, "悬赏标题不能为空")
        if (rawDesc.length < 10) throw ApiException(400, "悬赏描述太短，至少 10 个字")

        val requirement = BountyStructureParser.parse(title + " " + rawDesc)
        val id = bountyMapper.insert(userId, title, rawDesc, requirement.toJson(), 1)
        return toDto(bountyMapper.findById(id) ?: throw ApiException(500, "悬赏保存失败"), userId)
    }

    fun detail(id: Long, userId: Long): BountyDto =
        toDto(bountyMapper.findById(id) ?: throw ApiException(404, "悬赏不存在"), userId)

    fun setStatus(userId: Long, role: Int, id: Long, status: Int): BountyDto {
        if (status !in 0..2) throw ApiException(400, "状态值非法")
        val bounty = bountyMapper.findById(id) ?: throw ApiException(404, "悬赏不存在")
        if (role != Roles.ADMIN && bounty.publisherUserId != userId) throw ApiException(403, "只能管理自己发布的悬赏")
        bountyMapper.updateStatus(id, status)
        return toDto(bountyMapper.findById(id) ?: bounty, userId)
    }

    fun toDto(bounty: Bounty, currentUserId: Long): BountyDto {
        val id = bounty.id ?: 0
        return BountyDto(
            id = id,
            title = bounty.title,
            rawDesc = bounty.rawDesc,
            structuredJson = bounty.structuredJson,
            status = bounty.status,
            statusLabel = statusLabel(bounty.status),
            publisherId = bounty.publisherUserId,
            publisherName = bounty.publisherName,
            createdAt = bounty.createdAt,
            scanTaskCount = scanTaskMapper.countByBounty(id),
            highMatchCount = scanResultMapper.countHighMatchByBounty(id),
            mine = bounty.publisherUserId == currentUserId,
        )
    }

    private fun statusLabel(status: Int): String = when (status) {
        0 -> "草稿"
        1 -> "进行中"
        else -> "已停用"
    }
}
