package com.pgigi.agentclouddisk.controller

import com.pgigi.agentclouddisk.auth.CurrentUser
import com.pgigi.agentclouddisk.common.ApiResult
import com.pgigi.agentclouddisk.agent.BountyService
import com.pgigi.agentclouddisk.model.BountyDto
import com.pgigi.agentclouddisk.model.BountyRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/bounty")
class BountyController(private val bountyService: BountyService) {

    @GetMapping
    fun list(@RequestParam(required = false) status: Int?): ApiResult<List<BountyDto>> =
        ApiResult.ok(bountyService.list(CurrentUser.id(), CurrentUser.role(), status))

    @PostMapping
    fun create(@RequestBody body: BountyRequest): ApiResult<BountyDto> =
        ApiResult.ok(bountyService.create(CurrentUser.id(), CurrentUser.role(), body))

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): ApiResult<BountyDto> =
        ApiResult.ok(bountyService.detail(id, CurrentUser.id()))

    @PutMapping("/{id}/status")
    fun status(@PathVariable id: Long, @RequestParam status: Int): ApiResult<BountyDto> =
        ApiResult.ok(bountyService.setStatus(CurrentUser.id(), CurrentUser.role(), id, status))
}
