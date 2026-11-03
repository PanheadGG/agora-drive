package com.pgigi.agentclouddisk.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class JwtClaims(val userId: Long, val username: String, val role: Int)

/**
 * 极简 HS256 JWT：header.payload.signature，payload 为 JSON。
 * 演示项目不引入额外依赖，签名校验交给 HMAC-SHA256。
 */
@Component
class JwtUtil(
    @Value("\${app.jwt.secret}") private val secret: String,
    @Value("\${app.jwt.expire-hours}") private val expireHours: Long,
) {
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun create(userId: Long, username: String, role: Int): String {
        val header = encode("""{"alg":"HS256","typ":"JWT"}""")
        val exp = System.currentTimeMillis() / 1000 + expireHours * 3600
        val payload = encode("""{"sub":$userId,"name":"${escape(username)}","role":$role,"exp":$exp}""")
        val content = "$header.$payload"
        return "$content.${sign(content)}"
    }

    fun parse(token: String): JwtClaims? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val content = "${parts[0]}.${parts[1]}"
        if (!MessageDigest.isEqual(sign(content).toByteArray(), parts[2].toByteArray())) return null

        val json = runCatching { String(decoder.decode(parts[1]), Charsets.UTF_8) }.getOrNull() ?: return null
        val exp = longField(json, "exp") ?: return null
        if (exp * 1000 < System.currentTimeMillis()) return null
        return JwtClaims(
            userId = longField(json, "sub") ?: return null,
            username = stringField(json, "name") ?: "",
            role = longField(json, "role")?.toInt() ?: 0,
        )
    }

    private fun encode(text: String): String = encoder.encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun sign(content: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(content.toByteArray(Charsets.UTF_8)))
    }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun longField(json: String, name: String): Long? =
        Regex("\"" + name + "\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun stringField(json: String, name: String): String? {
        val raw = Regex("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
            ?.groupValues?.get(1) ?: return null
        return raw.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
