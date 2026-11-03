package com.pgigi.agentclouddisk.controller

import com.pgigi.agentclouddisk.common.ApiResult
import com.pgigi.agentclouddisk.model.AdminStatsDto
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
class AdminController(private val adminService: AdminService) {

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
}
