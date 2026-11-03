package com.pgigi.agentclouddisk.service

import com.pgigi.agentclouddisk.common.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 存储路径推演（见 docs/数据库设计.md）：
 * 正式文件 {storage_root}/{sha[0:2]}/{sha[2:4]}/{sha}
 * 临时分片 {temp_root}/{task_id}/{chunk_index}
 */
@Service
class StorageService(
    @Value("\${app.storage.root}") rootDir: String,
    @Value("\${app.storage.temp-root}") tempDir: String,
    @Value("\${app.upload.chunk-size}") val chunkSize: Long,
) {
    val root: Path = Path.of(rootDir).toAbsolutePath().normalize()
    val temp: Path = Path.of(tempDir).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
        Files.createDirectories(temp)
    }

    fun contentPath(sha256: String): Path =
        root.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256)

    fun taskDir(taskId: String): Path = temp.resolve(taskId)

    fun chunkPath(taskId: String, index: Int): Path = taskDir(taskId).resolve(index.toString())

    fun sha256Of(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 把临时文件落到正式路径；内容已存在时直接丢弃临时文件（秒传/去重） */
    fun commit(tempFile: Path, sha256: String): Path {
        val target = contentPath(sha256)
        Files.createDirectories(target.parent)
        if (Files.exists(target)) {
            Files.deleteIfExists(tempFile)
            return target
        }
        runCatching { Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE) }
            .recoverCatching { Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { throw ApiException(500, "写入存储失败: ${it.message}") }
        return target
    }

    fun deleteTaskDir(taskId: String) {
        val dir = taskDir(taskId)
        if (!Files.exists(dir)) return
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
