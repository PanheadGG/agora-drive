package com.pgigi.agentclouddisk.controller.market

import com.pgigi.agentclouddisk.agent.market.CollabCoordinator
import com.pgigi.agentclouddisk.agent.market.MarketService
import com.pgigi.agentclouddisk.auth.CurrentUser
import com.pgigi.agentclouddisk.common.ApiResult
import com.pgigi.agentclouddisk.common.Roles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 买方视图 + 市场目录 + Agent 协同 */
@RestController
@RequestMapping("/api/market")
class MarketController(
    private val marketService: MarketService,
    private val collab: CollabCoordinator,
) {

    // ---------- 产品目录（买方可见的只有描述） ----------

    @GetMapping("/listings")
    fun listings(
        @RequestParam(defaultValue = "") keyword: String,
        @RequestParam(defaultValue = "") domain: String,
        @RequestParam(defaultValue = "0") maxPriceCents: Long,
    ): ApiResult<Any> = ApiResult.ok(marketService.browse(MarketService.ListRequest(keyword.trim(), domain.trim(), maxPriceCents)))

    @GetMapping("/listings/{id}")
    fun listing(@PathVariable id: Long): ApiResult<Any> = ApiResult.ok(marketService.detail(id))

    // ---------- 采购需求 ----------

    @PostMapping("/demands")
    fun createDemand(@RequestBody body: MarketService.DemandRequest): ApiResult<Any> =
        ApiResult.ok(marketService.createDemand(CurrentUser.id(), body))

    @GetMapping("/demands")
    fun demands(): ApiResult<Any> = ApiResult.ok(marketService.listDemands(CurrentUser.id()))

    @PostMapping("/demands/{id}/match")
    fun match(@PathVariable id: Long): ApiResult<Any> =
        ApiResult.ok(marketService.matchDemand(CurrentUser.id(), id))

    // ---------- 推送 ----------

    @GetMapping("/pushes")
    fun pushes(): ApiResult<Any> = ApiResult.ok(marketService.listPushes(CurrentUser.id()))

    @PostMapping("/pushes/{id}/read")
    fun read(@PathVariable id: Long): ApiResult<Any> = ApiResult.ok(marketService.markRead(CurrentUser.id(), id))

    // ---------- 交付（最小化） ----------

    @PostMapping("/pushes/{id}/delivery")
    fun requestDelivery(@PathVariable id: Long, @RequestParam(defaultValue = "") note: String): ApiResult<Any> =
        ApiResult.ok(marketService.requestDelivery(CurrentUser.id(), id, note))

    @GetMapping("/deliveries")
    fun deliveries(): ApiResult<Any> = ApiResult.ok(marketService.listDeliveries(CurrentUser.id(), asSeller = false))

    // ---------- Agent 协同 ----------

    @PostMapping("/collab/invitations")
    fun invite(@RequestBody body: CollabCoordinator.InviteRequest): ApiResult<Any> =
        ApiResult.ok(collab.invite(CurrentUser.id(), body))

    @GetMapping("/collab")
    fun collabs(): ApiResult<Any> = ApiResult.ok(collab.list(CurrentUser.id(), CurrentUser.role() == Roles.ADMIN))

    @GetMapping("/collab/{id}")
    fun collabDetail(@PathVariable id: Long): ApiResult<Any> =
        ApiResult.ok(collab.detail(CurrentUser.id(), id, CurrentUser.role() == Roles.ADMIN))

    @PostMapping("/collab/{id}/accept")
    fun accept(@PathVariable id: Long): ApiResult<Any> = ApiResult.ok(collab.accept(CurrentUser.id(), id))

    @PostMapping("/collab/{id}/reject")
    fun reject(@PathVariable id: Long): ApiResult<Any> = ApiResult.ok(collab.reject(CurrentUser.id(), id))

    @PostMapping("/collab/{id}/contribute")
    fun contribute(@PathVariable id: Long, @RequestBody body: CollabCoordinator.ContributeRequest): ApiResult<Any> =
        ApiResult.ok(collab.contribute(CurrentUser.id(), id, body))

    @PostMapping("/collab/{id}/merge")
    fun merge(@PathVariable id: Long): ApiResult<Any> = ApiResult.ok(collab.merge(CurrentUser.id(), id))

    // ---------- 统计 ----------

    @GetMapping("/stats")
    fun stats(): ApiResult<Any> = ApiResult.ok(marketService.stats())
}
