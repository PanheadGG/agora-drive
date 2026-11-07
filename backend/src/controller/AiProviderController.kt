package com.pgigi.agentclouddisk.controller

import com.pgigi.agentclouddisk.agent.AiProviderService
import com.pgigi.agentclouddisk.agent.LlmClient
import com.pgigi.agentclouddisk.common.ApiResult
import com.pgigi.agentclouddisk.model.AiProviderDto
import com.pgigi.agentclouddisk.model.AiProviderRequest
import com.pgigi.agentclouddisk.model.AiProviderTestResult
import com.pgigi.agentclouddisk.model.LlmStatusDto
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 管理后台「模型管理」：多个自定义 AI 平台的增删改查、连通性测试与优先级编排 */
@RestController
@RequestMapping("/api/admin/ai-providers")
class AiProviderController(
    private val providerService: AiProviderService,
    private val llmClient: LlmClient,
) {

    @GetMapping
    fun list(): ApiResult<List<AiProviderDto>> = ApiResult.ok(providerService.list())

    /** 当前链路状态：谁在用、谁在冷却、失败切换过几次、主密钥来源 */
    @GetMapping("/status")
    fun status(): ApiResult<LlmStatusDto> = ApiResult.ok(llmClient.statusDto())

    @PostMapping
    fun create(@RequestBody body: AiProviderRequest): ApiResult<AiProviderDto> =
        ApiResult.ok(providerService.create(body))

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody body: AiProviderRequest): ApiResult<AiProviderDto> =
        ApiResult.ok(providerService.update(id, body))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ApiResult<Unit> {
        providerService.delete(id)
        return ApiResult.ok(Unit)
    }

    @PostMapping("/{id}/test")
    fun test(@PathVariable id: Long): ApiResult<AiProviderTestResult> =
        ApiResult.ok(providerService.test(id))

    @PutMapping("/{id}/status")
    fun setStatus(@PathVariable id: Long, @RequestParam status: Int): ApiResult<AiProviderDto> =
        ApiResult.ok(providerService.setStatus(id, status))

    @PutMapping("/{id}/priority")
    fun setPriority(@PathVariable id: Long, @RequestParam priority: Int): ApiResult<AiProviderDto> =
        ApiResult.ok(providerService.setPriority(id, priority))
}
