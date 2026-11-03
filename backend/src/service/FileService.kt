package com.pgigi.agentclouddisk.service

import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.common.QuotaExceededException
import com.pgigi.agentclouddisk.mapper.FileMetaMapper
import com.pgigi.agentclouddisk.mapper.UserFileMapper
import com.pgigi.agentclouddisk.mapper.UserMapper
import com.pgigi.agentclouddisk.model.DirResponse
import com.pgigi.agentclouddisk.model.FileDto
import com.pgigi.agentclouddisk.model.FileRow
import com.pgigi.agentclouddisk.model.QuotaDto
import com.pgigi.agentclouddisk.model.UserDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FileService(
    private val fileMapper: UserFileMapper,
    private val metaMapper: FileMetaMapper,
    private val userMapper: UserMapper,
) {

    fun list(userId: Long, parentId: Long?, keyword: String): DirResponse {
        if (parentId != null) requireFolder(userId, parentId)
        val rows = fileMapper.listDir(userId, parentId, keyword.trim())
        return DirResponse(parentId = parentId, breadcrumb = breadcrumb(userId, parentId), items = rows.map { it.toDto() })
    }

    fun breadcrumb(userId: Long, parentId: Long?): List<FileDto> {
        val chain = ArrayDeque<FileDto>()
        var current = parentId
        var guard = 0
        while (current != null && guard++ < 64) {
            val row = fileMapper.findRow(current, userId) ?: break
            chain.addFirst(row.toDto())
            current = row.parentId
        }
        return chain.toList()
    }

    fun get(userId: Long, id: Long): FileDto =
        (fileMapper.findRow(id, userId) ?: throw ApiException(404, "文件不存在")).toDto()

    fun createFolder(userId: Long, parentId: Long?, rawName: String): FileDto {
        val name = validateName(rawName)
        if (parentId != null) requireFolder(userId, parentId)
        if (fileMapper.countSameName(userId, parentId, name) > 0) throw ApiException(400, "同名文件或文件夹已存在")
        val id = fileMapper.insert(userId, parentId, null, name, 1)
        return get(userId, id)
    }

    fun rename(userId: Long, id: Long, rawName: String): FileDto {
        val row = fileMapper.findRow(id, userId) ?: throw ApiException(404, "文件不存在")
        val name = validateName(rawName)
        if (fileMapper.countSameName(userId, row.parentId, name) > 0) throw ApiException(400, "同名文件或文件夹已存在")
        fileMapper.rename(id, userId, name)
        return get(userId, id)
    }

    fun moveToRecycle(userId: Long, id: Long) {
        fileMapper.findRow(id, userId) ?: throw ApiException(404, "文件不存在")
        fileMapper.markRecycle(id, userId)
    }

    fun recycleList(userId: Long): List<FileDto> = fileMapper.listRecycle(userId).map { it.toDto() }

    fun restore(userId: Long, id: Long) {
        val row = fileMapper.findRow(id, userId) ?: throw ApiException(404, "文件不存在")
        if (row.isRecycle != 1) throw ApiException(400, "该条目不在回收站中")
        val parentId = row.parentId
        if (parentId != null) {
            val parent = fileMapper.findRow(parentId, userId)
            if (parent == null || parent.isRecycle == 1) throw ApiException(400, "上级文件夹还在回收站，请先恢复上级文件夹")
        }
        if (fileMapper.countSameName(userId, row.parentId, row.fileName) > 0) {
            throw ApiException(400, "同名文件已存在，无法恢复")
        }
        fileMapper.restoreTree(id, userId)
    }

    @Transactional
    fun purge(userId: Long, id: Long) {
        val row = fileMapper.findRow(id, userId) ?: throw ApiException(404, "文件不存在")
        if (row.isRecycle != 1) throw ApiException(400, "只能彻底删除回收站中的条目")
        val size = fileMapper.subtreeSize(id, userId)
        fileMapper.deleteTree(id, userId)
        userMapper.reduceUsedSize(userId, size)
    }

    @Transactional
    fun emptyRecycle(userId: Long) {
        val size = fileMapper.recycleSize(userId)
        fileMapper.deleteAllRecycle(userId)
        userMapper.reduceUsedSize(userId, size)
    }

    fun quota(userId: Long): QuotaDto {
        val user = userMapper.findById(userId) ?: throw ApiException(401, "用户不存在")
        return QuotaDto(user.totalQuota, user.usedSize)
    }

    fun me(userId: Long): UserDto {
        val user = userMapper.findById(userId) ?: throw ApiException(401, "用户不存在")
        return UserDto.of(user)
    }

    /** 上传落库：校验配额 → file_meta 去重复用 → 写目录条目 */
    @Transactional
    fun registerFile(userId: Long, parentId: Long?, rawName: String, sha256: String, size: Long): FileDto {
        val name = validateName(rawName)
        if (parentId != null) requireFolder(userId, parentId)
        if (fileMapper.countSameName(userId, parentId, name) > 0) throw ApiException(400, "同名文件或文件夹已存在")
        val metaId = metaMapper.insertIfAbsent(sha256, size) ?: metaMapper.findBySha256(sha256)?.id
            ?: throw ApiException(500, "文件元数据写入失败")
        if (userMapper.addUsedSize(userId, size) == 0) throw QuotaExceededException()
        val id = fileMapper.insert(userId, parentId, metaId, name, 0)
        return get(userId, id)
    }

    fun requireOwned(userId: Long, id: Long): FileRow =
        fileMapper.findRow(id, userId) ?: throw ApiException(404, "文件不存在")

    private fun requireFolder(userId: Long, id: Long) {
        val row = fileMapper.findRow(id, userId)
        if (row == null || row.isFolder != 1) throw ApiException(404, "目标文件夹不存在")
    }

    private fun validateName(rawName: String): String {
        val name = rawName.trim()
        if (name.isEmpty()) throw ApiException(400, "名称不能为空")
        if (name.length > 255) throw ApiException(400, "名称过长")
        if (name.contains('/') || name.contains('\\')) throw ApiException(400, "名称不能包含斜杠")
        return name
    }

    companion object {
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
        private val TEXT_EXTS = setOf(
            "txt", "md", "json", "xml", "yml", "yaml", "csv", "log", "ini", "conf",
            "kt", "java", "js", "ts", "html", "css", "sql", "sh", "bat", "py",
        )

        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
        }

        fun FileRow.toDto(): FileDto {
            val folder = isFolder == 1
            val ext = extensionOf(fileName)
            val kind = when {
                folder -> "folder"
                ext in IMAGE_EXTS -> "image"
                ext in TEXT_EXTS -> "text"
                else -> "file"
            }
            return FileDto(
                id = id ?: 0,
                parentId = parentId,
                name = fileName,
                folder = folder,
                size = totalSize,
                ext = ext,
                sha256 = sha256,
                kind = kind,
                previewable = kind == "image" || kind == "text",
                updatedAt = updatedAt,
            )
        }
    }
}
