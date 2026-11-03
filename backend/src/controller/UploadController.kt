package com.pgigi.agentclouddisk.controller

import com.pgigi.agentclouddisk.auth.CurrentUser
import com.pgigi.agentclouddisk.common.ApiResult
import com.pgigi.agentclouddisk.model.ChunkUploadResponse
import com.pgigi.agentclouddisk.model.FileDto
import com.pgigi.agentclouddisk.model.InitUploadRequest
import com.pgigi.agentclouddisk.model.InitUploadResponse
import com.pgigi.agentclouddisk.model.MergeUploadRequest
import com.pgigi.agentclouddisk.service.UploadService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/upload")
class UploadController(private val uploadService: UploadService) {

    /** 小文件直传 */
    @PostMapping("/small")
    fun small(
        @RequestPart("file") file: MultipartFile,
        @RequestParam(required = false) parentId: Long?,
    ): ApiResult<FileDto> = ApiResult.ok(
        file.inputStream.use { uploadService.saveSmall(CurrentUser.id(), parentId, file.originalFilename ?: "未命名文件", it) }
    )

    /** 分片上传：初始化（可携带 sha256 触发秒传） */
    @PostMapping("/chunk/init")
    fun init(@RequestBody body: InitUploadRequest): ApiResult<InitUploadResponse> =
        ApiResult.ok(uploadService.init(CurrentUser.id(), body))

    /** 上传单个分片 */
    @PostMapping("/chunk")
    fun chunk(
        @RequestPart("chunk") chunk: MultipartFile,
        @RequestParam taskId: String,
        @RequestParam index: Int,
    ): ApiResult<ChunkUploadResponse> =
        ApiResult.ok(uploadService.saveChunk(CurrentUser.id(), taskId, index, chunk.bytes))

    /** 合并分片并落库 */
    @PostMapping("/chunk/merge")
    fun merge(@RequestBody body: MergeUploadRequest): ApiResult<FileDto> =
        ApiResult.ok(uploadService.merge(CurrentUser.id(), body.taskId, body.parentId, body.fileName))

    @GetMapping("/chunk/progress")
    fun progress(@RequestParam taskId: String): ApiResult<ChunkUploadResponse> =
        ApiResult.ok(uploadService.progress(CurrentUser.id(), taskId))
}
