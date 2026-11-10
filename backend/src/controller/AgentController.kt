package com.pgigi.agentclouddisk.controller

import com.pgigi.agentclouddisk.agent.AgentScanService
import com.pgigi.agentclouddisk.auth.CurrentUser
import com.pgigi.agentclouddisk.common.ApiResult
import com.pgigi.agentclouddisk.model.FolderNodeDto
import com.pgigi.agentclouddisk.model.ReviewRequest
import com.pgigi.agentclouddisk.model.ScanPreviewDto
import com.pgigi.agentclouddisk.model.ScanProgressDto
import com.pgigi.agentclouddisk.model.ScanRequest
import com.pgigi.agentclouddisk.model.ScanResultDto
import com.pgigi.agentclouddisk.model.ScanStartDto
import com.pgigi.agentclouddisk.model.ScanTaskDetailDto
import com.pgigi.agentclouddisk.model.ScanTaskRowDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/agent")
class AgentController(private val agentScanService: AgentScanService) {

    /** 供用户勾选授权范围：本人所有未回收文件夹 */
    @GetMapping("/folders")
    fun folders(): ApiResult<List<FolderNodeDto>> = ApiResult.ok(agentScanService.folders(CurrentUser.id()))

    /** 模型 / OCR 能力状态 */
    @GetMapping("/llm-status")
    fun llmStatus(): ApiResult<Map<String, Any?>> = ApiResult.ok(agentScanService.llmStatus())

    /** 发起扫描：立即返回 taskId，扫描在线程池里异步执行 */
    @PostMapping("/scan")
    fun scan(@RequestBody body: ScanRequest): ApiResult<ScanStartDto> =
        ApiResult.ok(agentScanService.startScan(CurrentUser.id(), body))

    /** 扫描过程实时进度（前端轮询） */
    @GetMapping("/tasks/{id}/progress")
    fun progress(@PathVariable id: Long): ApiResult<ScanProgressDto> =
        ApiResult.ok(agentScanService.progress(id, CurrentUser.id(), CurrentUser.role()))

    @GetMapping("/tasks")
    fun tasks(): ApiResult<List<ScanTaskRowDto>> = ApiResult.ok(agentScanService.myTasks(CurrentUser.id()))

    @GetMapping("/tasks/{id}")
    fun detail(@PathVariable id: Long): ApiResult<ScanTaskDetailDto> =
        ApiResult.ok(agentScanService.detail(id, CurrentUser.id(), CurrentUser.role()))

    /** 扫描结果的内容预览（正文 / 表格行 / 图片标记） */
    @GetMapping("/results/{id}/preview")
    fun preview(@PathVariable id: Long): ApiResult<ScanPreviewDto> =
        ApiResult.ok(agentScanService.preview(CurrentUser.id(), CurrentUser.role(), id))

    @PutMapping("/results/{id}/review")
    fun review(@PathVariable id: Long, @RequestBody body: ReviewRequest): ApiResult<ScanResultDto> =
        ApiResult.ok(agentScanService.review(CurrentUser.id(), id, body))
}
