package com.pgigi.agentclouddisk.crypto

import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 加密结果：密文、随机 IV、GCM 校验标签分别入库 */
data class EncryptedValue(
    val cipherText: ByteArray,
    val iv: ByteArray,
    val tag: ByteArray,
    val algo: String,
)

/**
 * 应用层对称加密（AES-256-GCM）。
 *
 * 主密钥来源优先级：
 * 1. 环境变量 APP_MASTER_KEY（或配置项 app.crypto.master-key），Base64 或 64 位十六进制，必须 32 字节；
 * 2. 本地主密钥文件 app.crypto.master-key-file（默认 ./data/master.key，已在 .gitignore 中忽略）。
 *
 * 主密钥绝不入库、不入代码、不写日志；每次加密生成独立随机 12 字节 IV。
 */
@Component
class AesGcmCrypto(private val environment: Environment) {

    private val log = LoggerFactory.getLogger(AesGcmCrypto::class.java)
    private val random = SecureRandom()

    private val keyFile: Path = Path.of(
        environment.getProperty("app.crypto.master-key-file", "./data/master.key") ?: "./data/master.key"
    ).toAbsolutePath().normalize()

    /** 主密钥与其来源 */
    private val resolved: Pair<ByteArray, String> = resolveMasterKey()
    private val masterKey: ByteArray = resolved.first
    val keySource: String = resolved.second

    init {
        val level = if (keySource == "generated") "WARN" else "INFO"
        log.info("AES-GCM master key ready: source={} fingerprint={} ({})", keySource, fingerprint(masterKey), level)
    }

    /** 环境变量优先，其次本地密钥文件，最后生成一份（仅本机可读） */
    private fun resolveMasterKey(): Pair<ByteArray, String> {
        val configured = environment.getProperty("app.crypto.master-key", "")?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            val key = parseKey(configured)
                ?: throw IllegalStateException("APP_MASTER_KEY 必须是 32 字节的 Base64 或 64 位十六进制")
            return key to "env"
        }
        if (Files.exists(keyFile)) {
            val stored = Files.readString(keyFile).trim()
            val key = parseKey(stored) ?: throw IllegalStateException("主密钥文件内容非法: " + keyFile)
            return key to "file"
        }
        val generated = ByteArray(KEY_LENGTH).also { random.nextBytes(it) }
        Files.createDirectories(keyFile.parent)
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated))
        runCatching {
            Files.setPosixFilePermissions(
                keyFile,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
        log.warn(
            "APP_MASTER_KEY 未配置，已生成主密钥文件 {}（不入库、不提交代码）。生产环境请改用环境变量注入。",
            keyFile,
        )
        return generated to "generated"
    }

    fun status(): Map<String, Any?> = linkedMapOf(
        "algo" to ALGO,
        "keySource" to keySource,
        "keyFingerprint" to fingerprint(masterKey),
        "keyFile" to if (keySource == "env") null else keyFile.toString(),
    )

    fun encrypt(plainText: String): EncryptedValue {
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val output = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val cipherText = output.copyOfRange(0, output.size - TAG_LENGTH)
        val tag = output.copyOfRange(output.size - TAG_LENGTH, output.size)
        return EncryptedValue(cipherText, iv, tag, ALGO)
    }

    fun decrypt(cipherText: ByteArray, iv: ByteArray, tag: ByteArray, algo: String = ALGO): String {
        require(algo == ALGO) { "不支持的加密算法: " + algo }
        val joined = ByteArray(cipherText.size + tag.size)
        System.arraycopy(cipherText, 0, joined, 0, cipherText.size)
        System.arraycopy(tag, 0, joined, cipherText.size, tag.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(joined), Charsets.UTF_8)
    }

    /** 脱敏展示，仅保留前缀与末四位 */
    fun mask(plainText: String?): String {
        if (plainText.isNullOrEmpty()) return ""
        if (plainText.length <= 8) return "****"
        val prefix = if (plainText.startsWith("sk-")) "sk-" else plainText.take(3)
        return prefix + "****" + plainText.takeLast(4)
    }

    private fun parseKey(text: String): ByteArray? {
        val raw = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
            ?: runCatching { text.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
            ?: return null
        return raw.takeIf { it.size == KEY_LENGTH }
    }

    private fun fingerprint(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).take(4).joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_LENGTH = 32
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val TAG_LENGTH = 16
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ALGO = "aes-gcm-256"
    }
}
