package com.pgigi.agentclouddisk.service

import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.mapper.BountyMapper
import com.pgigi.agentclouddisk.mapper.ScanResultMapper
import com.pgigi.agentclouddisk.mapper.ScanTaskMapper
import com.pgigi.agentclouddisk.mapper.UploadTaskMapper
import com.pgigi.agentclouddisk.mapper.UserFileMapper
import com.pgigi.agentclouddisk.mapper.UserMapper
import com.pgigi.agentclouddisk.model.AdminStatsDto
import com.pgigi.agentclouddisk.model.UserDto
import org.springframework.stereotype.Service

@Service
class AdminService(
    private val userMapper: UserMapper,
    private val fileMapper: UserFileMapper,
    private val taskMapper: UploadTaskMapper,
    private val bountyMapper: BountyMapper,
    private val scanTaskMapper: ScanTaskMapper,
    private val scanResultMapper: ScanResultMapper,
) {

    fun stats(): AdminStatsDto = AdminStatsDto(
        userCount = userMapper.countAll(),
        fileCount = fileMapper.countByType(0, 0),
        folderCount = fileMapper.countByType(1, 0),
        totalUsed = userMapper.sumUsedSize(),
        recycleCount = fileMapper.countRecycle(),
        bountyCount = bountyMapper.countAll(),
        activeBountyCount = bountyMapper.countActive(),
        scanTaskCount = scanTaskMapper.countByStatus(2) + scanTaskMapper.countByStatus(1),
        pendingReviewCount = scanResultMapper.countByReview(0),
    )

    fun users(keyword: String): List<UserDto> = userMapper.list(keyword.trim()).map { UserDto.of(it) }

    fun setStatus(id: Long, status: Int): UserDto {
        if (status != 0 && status != 1) throw ApiException(400, "状态值非法")
        val user = userMapper.findById(id) ?: throw ApiException(404, "用户不存在")
        if (user.role == 1 && status == 0) throw ApiException(400, "不能禁用管理员账号")
        userMapper.updateStatus(id, status)
        return UserDto.of(userMapper.findById(id)!!)
    }

    fun setQuota(id: Long, quota: Long): UserDto {
        val user = userMapper.findById(id) ?: throw ApiException(404, "用户不存在")
        if (quota < user.usedSize) throw ApiException(400, "配额不能小于已使用空间")
        if (quota <= 0) throw ApiException(400, "配额必须大于 0")
        userMapper.updateQuota(id, quota)
        return UserDto.of(userMapper.findById(id)!!)
    }

    fun pendingUploadTasks(): Long = taskMapper.countByStatus(0)
}
