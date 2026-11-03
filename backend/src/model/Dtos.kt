package com.pgigi.agentclouddisk.model

data class UserDto(
    val id: Long,
    val username: String,
    val role: Int,
    val status: Int,
    val totalQuota: Long,
    val usedSize: Long,
    val createdAt: String? = null,
) {
    companion object {
        fun of(u: User) = UserDto(
            id = u.id ?: 0,
            username = u.username,
            role = u.role,
            status = u.status,
            totalQuota = u.totalQuota,
            usedSize = u.usedSize,
            createdAt = u.createdAt,
        )
    }
}

data class LoginResponse(val token: String, val user: UserDto)

data class FileDto(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val folder: Boolean,
    val size: Long,
    val ext: String,
    val sha256: String?,
    val kind: String,
    val previewable: Boolean,
    val updatedAt: String?,
)

data class DirResponse(
    val parentId: Long?,
    val breadcrumb: List<FileDto>,
    val items: List<FileDto>,
)

data class QuotaDto(val totalQuota: Long, val usedSize: Long)

data class InitUploadRequest(
    val fileName: String = "",
    val totalSize: Long = 0,
    val parentId: Long? = null,
    val sha256: String? = null,
)

data class MergeUploadRequest(val taskId: String = "", val parentId: Long? = null, val fileName: String = "")

data class InitUploadResponse(
    val instant: Boolean,
    val taskId: String?,
    val chunkSize: Long,
    val totalChunk: Int,
    val uploaded: List<Int>,
    val file: FileDto? = null,
)

data class ChunkUploadResponse(val taskId: String, val finishedChunk: Int, val totalChunk: Int)

data class AdminStatsDto(
    val userCount: Long,
    val fileCount: Long,
    val folderCount: Long,
    val totalUsed: Long,
    val recycleCount: Long,
)

data class FolderRequest(val parentId: Long? = null, val name: String = "")

data class RenameRequest(val name: String = "")
