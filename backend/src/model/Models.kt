package com.pgigi.agentclouddisk.model

data class User(
    var id: Long? = null,
    var username: String = "",
    var password: String = "",
    var totalQuota: Long = 0,
    var usedSize: Long = 0,
    var status: Int = 1,
    var role: Int = 0,
    var createdAt: String? = null,
)

data class FileMeta(
    var id: Long? = null,
    var sha256: String = "",
    var totalSize: Long = 0,
)

data class UserFile(
    var id: Long? = null,
    var userId: Long = 0,
    var parentId: Long? = null,
    var metaId: Long? = null,
    var fileName: String = "",
    var isFolder: Int = 0,
    var isRecycle: Int = 0,
    var createdAt: String? = null,
    var updatedAt: String? = null,
)

/** user_file 左连 file_meta 的目录行视图 */
data class FileRow(
    var id: Long? = null,
    var parentId: Long? = null,
    var metaId: Long? = null,
    var fileName: String = "",
    var isFolder: Int = 0,
    var isRecycle: Int = 0,
    var totalSize: Long = 0,
    var sha256: String? = null,
    var createdAt: String? = null,
    var updatedAt: String? = null,
)

data class UploadTask(
    var id: Long? = null,
    var userId: Long = 0,
    var taskId: String = "",
    var fileName: String = "",
    var totalSize: Long = 0,
    var totalChunk: Int = 0,
    var sha256: String? = null,
    var finishedChunk: Int = 0,
    var status: Int = 0,
)
