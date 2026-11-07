package com.pgigi.agentclouddisk.mapper

import com.pgigi.agentclouddisk.model.AiProvider
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface AiProviderMapper {

    @Select(
        """SELECT id, provider, name, base_url, model, encrypted_key, iv, gcm_tag, encrypt_algo,
                  priority, status, timeout_seconds, last_status, last_error,
                  to_char(last_checked_at, 'YYYY-MM-DD HH24:MI:SS') AS last_checked_at, last_latency_ms,
                  success_count, failure_count,
                  to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at
           FROM ai_provider ORDER BY priority, id"""
    )
    fun listAll(): List<AiProvider>

    @Select(
        """SELECT id, provider, name, base_url, model, encrypted_key, iv, gcm_tag, encrypt_algo,
                  priority, status, timeout_seconds, last_status, last_error,
                  to_char(last_checked_at, 'YYYY-MM-DD HH24:MI:SS') AS last_checked_at, last_latency_ms,
                  success_count, failure_count,
                  to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at
           FROM ai_provider WHERE id = #{id}"""
    )
    fun findById(@Param("id") id: Long): AiProvider?

    @Select(
        """SELECT id, provider, name, base_url, model, encrypted_key, iv, gcm_tag, encrypt_algo,
                  priority, status, timeout_seconds, last_status, last_error,
                  to_char(last_checked_at, 'YYYY-MM-DD HH24:MI:SS') AS last_checked_at, last_latency_ms,
                  success_count, failure_count,
                  to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at
           FROM ai_provider WHERE status = 1 ORDER BY priority, id"""
    )
    fun listEnabled(): List<AiProvider>

    /** 用 @Select 承接 RETURNING id，@Insert 只会返回受影响行数 */
    @Select(
        """INSERT INTO ai_provider (provider, name, base_url, model, encrypted_key, iv, gcm_tag, encrypt_algo,
                  priority, status, timeout_seconds)
           VALUES (#{provider}, #{name}, #{baseUrl}, #{model}, #{encryptedKey,jdbcType=VARBINARY},
                   #{iv,jdbcType=VARBINARY}, #{gcmTag,jdbcType=VARBINARY},
                   #{encryptAlgo}, #{priority}, #{status}, #{timeoutSeconds})
           RETURNING id"""
    )
    fun insert(provider: AiProvider): Long

    @Update(
        """UPDATE ai_provider SET provider = #{provider}, name = #{name}, base_url = #{baseUrl}, model = #{model},
                  priority = #{priority}, status = #{status}, timeout_seconds = #{timeoutSeconds},
                  updated_at = CURRENT_TIMESTAMP
           WHERE id = #{id}"""
    )
    fun updateMeta(provider: AiProvider): Int

    @Update(
        """UPDATE ai_provider SET encrypted_key = #{encryptedKey,jdbcType=VARBINARY},
                  iv = #{iv,jdbcType=VARBINARY}, gcm_tag = #{gcmTag,jdbcType=VARBINARY},
                  encrypt_algo = #{encryptAlgo}, updated_at = CURRENT_TIMESTAMP
           WHERE id = #{id}"""
    )
    fun updateKey(provider: AiProvider): Int

    @Update("""UPDATE ai_provider SET status = #{status}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    @Update("""UPDATE ai_provider SET priority = #{priority}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun updatePriority(@Param("id") id: Long, @Param("priority") priority: Int): Int

    @Delete("""DELETE FROM ai_provider WHERE id = #{id}""")
    fun delete(@Param("id") id: Long): Int

    @Update(
        """UPDATE ai_provider SET last_status = #{lastStatus}, last_error = #{lastError},
                  last_checked_at = CURRENT_TIMESTAMP, last_latency_ms = #{latencyMs},
                  success_count = success_count + #{successDelta}, failure_count = failure_count + #{failureDelta},
                  updated_at = CURRENT_TIMESTAMP
           WHERE id = #{id}"""
    )
    fun recordHealth(
        @Param("id") id: Long,
        @Param("lastStatus") lastStatus: String,
        @Param("lastError") lastError: String?,
        @Param("latencyMs") latencyMs: Long,
        @Param("successDelta") successDelta: Int,
        @Param("failureDelta") failureDelta: Int,
    ): Int

    @Select("""SELECT COUNT(*) FROM ai_provider""")
    fun countAll(): Long

    @Select("""SELECT COUNT(*) FROM ai_provider WHERE status = 1""")
    fun countEnabled(): Long
}
