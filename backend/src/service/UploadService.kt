package com.pgigi.agentclouddisk.service

import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.mapper.FileMetaMapper
import com.pgigi.agentclouddisk.mapper.UploadTaskMapper
import com.pgigi.agentclouddisk.model.ChunkUploadResponse
import com.pgigi.agentclouddisk.model.FileDto
import com.pgigi.agentclouddisk.model.InitUploadRequest
import com.pgigi.agentclouddisk.model.InitUploadResponse
import com.pgigi.agentclouddisk.model.UploadTask
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

@Service
class UploadService(
    private val storage: StorageService,
    private val fileService: FileService,
    private val taskMapper: UploadTaskMapper,
    private val metaMapper: FileMetaMapper,
) {

    /** 小文件直传：落临时文件 → 计算 sha256 → 落正式存储 → 登记目录条目 */
    fun saveSmall(userId: Long, parentId: Long?, originalName: String, input: InputStream): FileDto {
        val temp = Files.createTempFile(storage.temp, "small-", ".part")
        try {
            input.use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
            val size = Files.size(temp)
            if (size <= 0) throw ApiException(400, "不能上传空文件")
            val sha = storage.sha256Of(temp)
            storage.commit(temp, sha)
            return fileService.registerFile(userId, parentId, originalName, sha, size)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun init(userId: Long, req: InitUploadRequest): InitUploadResponse {
        val name = req.fileName.trim()
        if (name.isEmpty()) throw ApiException(400, "文件名不能为空")
        if (req.totalSize <= 0) throw ApiException(400, "文件大小非法")
        val totalChunk = ((req.totalSize + storage.chunkSize - 1) / storage.chunkSize).toInt().coerceAtLeast(1)
        val sha = req.sha256?.trim()?.lowercase()?.takeIf { it.length == 64 }

        // 秒传：文件内容已在存储中，直接建引用
        if (sha != null) {
            val meta = metaMapper.findBySha256(sha)
            if (meta != null && meta.totalSize == req.totalSize && Files.exists(storage.contentPath(sha))) {
                val dto = fileService.registerFile(userId, req.parentId, name, sha, req.totalSize)
                return InitUploadResponse(true, null, storage.chunkSize, totalChunk, emptyList(), dto)
            }
        }

        val taskId = UUID.randomUUID().toString().replace("-", "")
        taskMapper.insert(userId, taskId, name, req.totalSize, totalChunk)
        val uploaded = existingChunks(taskId, totalChunk)
        taskMapper.updateProgress(taskId, uploaded.size, 0, null)
        return InitUploadResponse(false, taskId, storage.chunkSize, totalChunk, uploaded, null)
    }

    fun saveChunk(userId: Long, taskId: String, index: Int, bytes: ByteArray): ChunkUploadResponse {
        val task = ownedTask(userId, taskId)
        if (task.status != 0) throw ApiException(400, "上传任务已结束")
        if (index < 0 || index >= task.totalChunk) throw ApiException(400, "分片序号越界")
        val dir = storage.taskDir(taskId)
        Files.createDirectories(dir)
        Files.write(storage.chunkPath(taskId, index), bytes)
        val finished = existingChunks(taskId, task.totalChunk).size
        taskMapper.updateProgress(taskId, finished, 0, null)
        return ChunkUploadResponse(taskId, finished, task.totalChunk)
    }

    fun merge(userId: Long, taskId: String, parentId: Long?, fileName: String?): FileDto {
        val task = ownedTask(userId, taskId)
        if (task.status != 0) throw ApiException(400, "上传任务已结束")
        val missing = (0 until task.totalChunk).filterNot { Files.exists(storage.chunkPath(taskId, it)) }
        if (missing.isNotEmpty()) throw ApiException(400, "分片缺失: " + missing.take(10).joinToString(","))

        val merged = Files.createTempFile(storage.temp, "merge-", ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            Files.newOutputStream(merged).use { out ->
                val buffer = ByteArray(1 shl 16)
                for (i in 0 until task.totalChunk) {
                    Files.newInputStream(storage.chunkPath(taskId, i)).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            val size = Files.size(merged)
            if (size != task.totalSize) throw ApiException(400, "合并后大小与声明不一致")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            storage.commit(merged, sha)
            val name = fileName?.trim()?.takeIf { it.isNotEmpty() } ?: task.fileName
            val dto = fileService.registerFile(userId, parentId, name, sha, size)
            taskMapper.updateProgress(taskId, task.totalChunk, 1, sha)
            storage.deleteTaskDir(taskId)
            return dto
        } finally {
            Files.deleteIfExists(merged)
        }
    }

    fun progress(userId: Long, taskId: String): ChunkUploadResponse {
        val task = ownedTask(userId, taskId)
        return ChunkUploadResponse(taskId, existingChunks(taskId, task.totalChunk).size, task.totalChunk)
    }

    private fun ownedTask(userId: Long, taskId: String): UploadTask {
        val task = taskMapper.findByTaskId(taskId) ?: throw ApiException(404, "上传任务不存在")
        if (task.userId != userId) throw ApiException(403, "无权操作该上传任务")
        if (taskMapper.isAlive(taskId) != true) throw ApiException(400, "上传任务已过期，请重新上传")
        return task
    }

    private fun existingChunks(taskId: String, totalChunk: Int): List<Int> {
        val dir: Path = storage.taskDir(taskId)
        if (!Files.exists(dir)) return emptyList()
        return (0 until totalChunk).filter { Files.exists(storage.chunkPath(taskId, it)) }
    }
}
