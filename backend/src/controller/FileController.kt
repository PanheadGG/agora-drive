package com.pgigi.agentclouddisk.controller

import com.pgigi.agentclouddisk.auth.CurrentUser
import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.common.ApiResult
import com.pgigi.agentclouddisk.model.DirResponse
import com.pgigi.agentclouddisk.model.FileDto
import com.pgigi.agentclouddisk.model.FolderRequest
import com.pgigi.agentclouddisk.model.QuotaDto
import com.pgigi.agentclouddisk.model.RenameRequest
import com.pgigi.agentclouddisk.service.FileService
import com.pgigi.agentclouddisk.service.StorageService
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.nio.file.Files

@RestController
@RequestMapping("/api/files")
class FileController(
    private val fileService: FileService,
    private val storage: StorageService,
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) parentId: Long?,
        @RequestParam(defaultValue = "") keyword: String,
    ): ApiResult<DirResponse> = ApiResult.ok(fileService.list(CurrentUser.id(), parentId, keyword))

    @GetMapping("/quota")
    fun quota(): ApiResult<QuotaDto> = ApiResult.ok(fileService.quota(CurrentUser.id()))

    @PostMapping("/folder")
    fun createFolder(@RequestBody body: FolderRequest): ApiResult<FileDto> =
        ApiResult.ok(fileService.createFolder(CurrentUser.id(), body.parentId, body.name))

    @PutMapping("/{id}/rename")
    fun rename(@PathVariable id: Long, @RequestBody body: RenameRequest): ApiResult<FileDto> =
        ApiResult.ok(fileService.rename(CurrentUser.id(), id, body.name))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ApiResult<Unit> {
        fileService.moveToRecycle(CurrentUser.id(), id)
        return ApiResult.ok(Unit)
    }

    @GetMapping("/recycle")
    fun recycle(): ApiResult<List<FileDto>> = ApiResult.ok(fileService.recycleList(CurrentUser.id()))

    @PostMapping("/recycle/{id}/restore")
    fun restore(@PathVariable id: Long): ApiResult<Unit> {
        fileService.restore(CurrentUser.id(), id)
        return ApiResult.ok(Unit)
    }

    @DeleteMapping("/recycle/{id}")
    fun purge(@PathVariable id: Long): ApiResult<Unit> {
        fileService.purge(CurrentUser.id(), id)
        return ApiResult.ok(Unit)
    }

    @DeleteMapping("/recycle")
    fun emptyRecycle(): ApiResult<Unit> {
        fileService.emptyRecycle(CurrentUser.id())
        return ApiResult.ok(Unit)
    }

    @GetMapping("/{id}/content")
    fun content(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "false") download: Boolean,
    ): ResponseEntity<Resource> {
        val userId = CurrentUser.id()
        val row = fileService.requireOwned(userId, id)
        if (row.isFolder == 1) throw ApiException(400, "文件夹不支持预览")
        val dto = fileService.get(userId, id)
        val sha = row.sha256 ?: throw ApiException(500, "文件内容缺失")
        val path = storage.contentPath(sha)
        if (!Files.exists(path)) throw ApiException(404, "文件内容不存在")

        val disposition = if (download) ContentDisposition.attachment() else ContentDisposition.inline()
        return ResponseEntity.ok()
            .contentType(mediaTypeOf(dto))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(dto.name, StandardCharsets.UTF_8).build().toString())
            .body(FileSystemResource(path))
    }

    private fun mediaTypeOf(dto: FileDto): MediaType = when (dto.kind) {
        "image" -> when (dto.ext) {
            "png" -> MediaType.IMAGE_PNG
            "gif" -> MediaType.IMAGE_GIF
            "webp" -> MediaType.parseMediaType("image/webp")
            "bmp" -> MediaType.parseMediaType("image/bmp")
            "svg" -> MediaType.parseMediaType("image/svg+xml")
            else -> MediaType.IMAGE_JPEG
        }
        "text" -> MediaType.parseMediaType("text/plain;charset=UTF-8")
        else -> MediaType.APPLICATION_OCTET_STREAM
    }
}
