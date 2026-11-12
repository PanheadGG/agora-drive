package com.pgigi.agentclouddisk.mapper

import com.pgigi.agentclouddisk.model.ScanTask
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface ScanTaskMapper {

    @Select(
        """INSERT INTO agent_scan_task (bounty_id, user_id, auth_scope_json, task_status, ai_enabled)
           VALUES (#{bountyId}, #{userId}, #{authScopeJson}::jsonb, 0, #{aiEnabled})
           RETURNING id"""
    )
    fun insert(
        @Param("bountyId") bountyId: Long,
        @Param("userId") userId: Long,
        @Param("authScopeJson") authScopeJson: String,
        @Param("aiEnabled") aiEnabled: Int = 0,
    ): Long

    @Update(
        """UPDATE agent_scan_task
           SET task_status = #{status}, total_file_count = #{total}, success_parse_count = #{success},
               fail_parse_count = #{fail},
               finished_at = CASE WHEN #{status} IN (2, 3) THEN CURRENT_TIMESTAMP ELSE finished_at END
           WHERE id = #{id}"""
    )
    fun updateProgress(
        @Param("id") id: Long,
        @Param("status") status: Int,
        @Param("total") total: Int,
        @Param("success") success: Int,
        @Param("fail") fail: Int,
    ): Int

    @Select(
        """SELECT t.id, t.bounty_id, t.user_id, t.auth_scope_json::text AS auth_scope_json, t.task_status,
                  t.total_file_count, t.success_parse_count, t.fail_parse_count, t.ai_enabled,
                  to_char(t.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(t.finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at,
                  b.title AS bounty_title, u.username AS user_name
           FROM agent_scan_task t
           JOIN data_bounty b ON b.id = t.bounty_id
           JOIN "user" u ON u.id = t.user_id
           WHERE t.id = #{id}"""
    )
    fun findById(@Param("id") id: Long): ScanTask?

    @Select(
        """SELECT t.id, t.bounty_id, t.user_id, t.auth_scope_json::text AS auth_scope_json, t.task_status,
                  t.total_file_count, t.success_parse_count, t.fail_parse_count, t.ai_enabled,
                  to_char(t.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(t.finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at,
                  b.title AS bounty_title, u.username AS user_name
           FROM agent_scan_task t
           JOIN data_bounty b ON b.id = t.bounty_id
           JOIN "user" u ON u.id = t.user_id
           WHERE t.user_id = #{userId}
           ORDER BY t.id DESC"""
    )
    fun listByUser(@Param("userId") userId: Long): List<ScanTask>

    @Select(
        """SELECT t.id, t.bounty_id, t.user_id, t.auth_scope_json::text AS auth_scope_json, t.task_status,
                  t.total_file_count, t.success_parse_count, t.fail_parse_count, t.ai_enabled,
                  to_char(t.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(t.finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at,
                  b.title AS bounty_title, u.username AS user_name
           FROM agent_scan_task t
           JOIN data_bounty b ON b.id = t.bounty_id
           JOIN "user" u ON u.id = t.user_id
           WHERE (#{bountyId,jdbcType=BIGINT} IS NULL OR t.bounty_id = #{bountyId,jdbcType=BIGINT})
           ORDER BY t.id DESC"""
    )
    fun listAll(@Param("bountyId") bountyId: Long?): List<ScanTask>

    @Select("""SELECT COUNT(*) FROM agent_scan_task WHERE bounty_id = #{bountyId}""")
    fun countByBounty(@Param("bountyId") bountyId: Long): Long

    @Select("""SELECT COUNT(*) FROM agent_scan_task WHERE task_status = #{status}""")
    fun countByStatus(@Param("status") status: Int): Long
}
