package com.pgigi.agentclouddisk.controller

import com.pgigi.agentclouddisk.agent.AgentScanService
import com.pgigi.agentclouddisk.agent.BountyService
import com.pgigi.agentclouddisk.auth.CurrentUser
import com.pgigi.agentclouddisk.common.ApiResult
import com.pgigi.agentclouddisk.common.Roles
import com.pgigi.agentclouddisk.model.AdminStatsDto
import com.pgigi.agentclouddisk.model.BountyDto
import com.pgigi.agentclouddisk.model.ScanTaskRowDto
import com.pgigi.agentclouddisk.model.UserDto
import com.pgigi.agentclouddisk.service.AdminService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val adminService: AdminService,
    private val bountyService: BountyService,
    private val agentScanService: AgentScanService,
) {

    @GetMapping("/stats")
    fun stats(): ApiResult<AdminStatsDto> = ApiResult.ok(adminService.stats())

    @GetMapping("/users")
    fun users(@RequestParam(defaultValue = "") keyword: String): ApiResult<List<UserDto>> =
        ApiResult.ok(adminService.users(keyword))

    @PutMapping("/users/{id}/status")
    fun setStatus(@PathVariable id: Long, @RequestParam status: Int): ApiResult<UserDto> =
        ApiResult.ok(adminService.setStatus(id, status))

    @PutMapping("/users/{id}/quota")
    fun setQuota(@PathVariable id: Long, @RequestParam totalQuota: Long): ApiResult<UserDto> =
        ApiResult.ok(adminService.setQuota(id, totalQuota))

    @GetMapping("/bounties")
    fun bounties(@RequestParam(required = false) status: Int?): ApiResult<List<BountyDto>> =
        ApiResult.ok(bountyService.list(CurrentUser.id(), Roles.ADMIN, status))

    @PutMapping("/bounties/{id}/status")
    fun bountyStatus(@PathVariable id: Long, @RequestParam status: Int): ApiResult<BountyDto> =
        ApiResult.ok(bountyService.setStatus(CurrentUser.id(), Roles.ADMIN, id, status))

    @GetMapping("/scan-tasks")
    fun scanTasks(@RequestParam(required = false) bountyId: Long?): ApiResult<List<ScanTaskRowDto>> =
        ApiResult.ok(agentScanService.auditTasks(bountyId))
}
