package com.pgigi.agentclouddisk.mapper.market

import com.pgigi.agentclouddisk.agent.market.CollabContribution
import com.pgigi.agentclouddisk.agent.market.CollabMember
import com.pgigi.agentclouddisk.agent.market.CollabSession
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface CollabMapper {

    @Select(
        """INSERT INTO collab_session (collab_code, collab_password_hash, template_key, template_label, title,
                  description, initiator_id, owner_id, partner_id, state, k_threshold, message)
           VALUES (#{collabCode}, #{passwordHash}, #{templateKey}, #{templateLabel}, #{title},
                   #{description}, #{initiatorId}, #{initiatorId}, #{initiatorId}, #{state}, #{kThreshold},
                   #{message,jdbcType=VARCHAR})
           RETURNING id"""
    )
    fun insertSession(
        @Param("collabCode") collabCode: String,
        @Param("passwordHash") passwordHash: String,
        @Param("templateKey") templateKey: String,
        @Param("templateLabel") templateLabel: String,
        @Param("title") title: String,
        @Param("description") description: String,
        @Param("initiatorId") initiatorId: Long,
        @Param("state") state: Int,
        @Param("kThreshold") kThreshold: Int,
        @Param("message") message: String?,
    ): Long

    @Select(
        """SELECT c.id, c.collab_code, c.collab_password_hash, c.template_key, c.template_label, c.title,
                  c.description, c.initiator_id, COALESCE(c.owner_id, c.initiator_id) AS owner_id,
                  c.partner_id, c.state, c.k_threshold, c.product_id, c.message,
                  to_char(c.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(c.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  iu.username AS initiator_name, pu.username AS partner_name, ou.username AS owner_name
           FROM collab_session c
           JOIN "user" iu ON iu.id = c.initiator_id
           LEFT JOIN "user" pu ON pu.id = c.partner_id
           LEFT JOIN "user" ou ON ou.id = COALESCE(c.owner_id, c.initiator_id)
           WHERE c.id = #{id}"""
    )
    fun findSession(@Param("id") id: Long): CollabSession?

    @Select(
        """SELECT c.id, c.collab_code, c.collab_password_hash, c.template_key, c.template_label, c.title,
                  c.description, c.initiator_id, COALESCE(c.owner_id, c.initiator_id) AS owner_id,
                  c.partner_id, c.state, c.k_threshold, c.product_id, c.message,
                  to_char(c.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(c.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  iu.username AS initiator_name, pu.username AS partner_name, ou.username AS owner_name
           FROM collab_session c
           JOIN "user" iu ON iu.id = c.initiator_id
           LEFT JOIN "user" pu ON pu.id = c.partner_id
           LEFT JOIN "user" ou ON ou.id = COALESCE(c.owner_id, c.initiator_id)
           WHERE UPPER(c.collab_code) = UPPER(#{code})"""
    )
    fun findByCode(@Param("code") code: String): CollabSession?

    @Select(
        """SELECT c.id, c.collab_code, c.collab_password_hash, c.template_key, c.template_label, c.title,
                  c.description, c.initiator_id, COALESCE(c.owner_id, c.initiator_id) AS owner_id,
                  c.partner_id, c.state, c.k_threshold, c.product_id, c.message,
                  to_char(c.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(c.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  iu.username AS initiator_name, pu.username AS partner_name, ou.username AS owner_name
           FROM collab_session c
           JOIN "user" iu ON iu.id = c.initiator_id
           LEFT JOIN "user" pu ON pu.id = c.partner_id
           LEFT JOIN "user" ou ON ou.id = COALESCE(c.owner_id, c.initiator_id)
           WHERE c.id IN (SELECT session_id FROM collab_member WHERE user_id = #{userId})
           ORDER BY c.id DESC"""
    )
    fun listForUser(@Param("userId") userId: Long): List<CollabSession>

    @Select(
        """SELECT c.id, c.collab_code, c.collab_password_hash, c.template_key, c.template_label, c.title,
                  c.description, c.initiator_id, COALESCE(c.owner_id, c.initiator_id) AS owner_id,
                  c.partner_id, c.state, c.k_threshold, c.product_id, c.message,
                  to_char(c.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(c.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  iu.username AS initiator_name, pu.username AS partner_name, ou.username AS owner_name
           FROM collab_session c
           JOIN "user" iu ON iu.id = c.initiator_id
           LEFT JOIN "user" pu ON pu.id = c.partner_id
           LEFT JOIN "user" ou ON ou.id = COALESCE(c.owner_id, c.initiator_id)
           ORDER BY c.id DESC"""
    )
    fun listAll(): List<CollabSession>

    @Update("""UPDATE collab_session SET state = #{state}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun updateState(@Param("id") id: Long, @Param("state") state: Int): Int

    @Update(
        """UPDATE collab_session SET product_id = #{productId}, state = #{state}, updated_at = CURRENT_TIMESTAMP
           WHERE id = #{id}"""
    )
    fun bindProduct(@Param("id") id: Long, @Param("productId") productId: Long, @Param("state") state: Int): Int

    @Update("""UPDATE collab_session SET partner_id = #{userId}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun setPartner(@Param("id") id: Long, @Param("userId") userId: Long): Int

    @Select("""SELECT COUNT(*) FROM collab_session""")
    fun countAll(): Long

    // ---------- 成员 ----------

    @Select(
        """INSERT INTO collab_member (session_id, user_id, role, scope_json, file_count, status)
           VALUES (#{sessionId}, #{userId}, #{role}, #{scopeJson}::jsonb, #{fileCount}, #{status})
           ON CONFLICT (session_id, user_id) DO UPDATE
           SET scope_json = EXCLUDED.scope_json, file_count = EXCLUDED.file_count, status = EXCLUDED.status
           RETURNING id"""
    )
    fun upsertMember(
        @Param("sessionId") sessionId: Long,
        @Param("userId") userId: Long,
        @Param("role") role: Int,
        @Param("scopeJson") scopeJson: String,
        @Param("fileCount") fileCount: Int,
        @Param("status") status: Int,
    ): Long

    @Update(
        """UPDATE collab_member SET rows_contributed = #{rows}, groups_contributed = #{groups},
                  status = #{status} WHERE session_id = #{sessionId} AND user_id = #{userId}"""
    )
    fun updateMemberProgress(
        @Param("sessionId") sessionId: Long,
        @Param("userId") userId: Long,
        @Param("rows") rows: Int,
        @Param("groups") groups: Int,
        @Param("status") status: Int,
    ): Int

    @Select(
        """SELECT m.id, m.session_id, m.user_id, m.role, m.scope_json::text AS scope_json, m.file_count,
                  m.rows_contributed, m.groups_contributed, m.status,
                  to_char(m.joined_at, 'YYYY-MM-DD HH24:MI:SS') AS joined_at, u.username AS user_name
           FROM collab_member m JOIN "user" u ON u.id = m.user_id
           WHERE m.session_id = #{sessionId}
           ORDER BY m.role DESC, m.id"""
    )
    fun listMembers(@Param("sessionId") sessionId: Long): List<CollabMember>

    @Select(
        """SELECT m.id, m.session_id, m.user_id, m.role, m.scope_json::text AS scope_json, m.file_count,
                  m.rows_contributed, m.groups_contributed, m.status,
                  to_char(m.joined_at, 'YYYY-MM-DD HH24:MI:SS') AS joined_at, u.username AS user_name
           FROM collab_member m JOIN "user" u ON u.id = m.user_id
           WHERE m.session_id = #{sessionId} AND m.user_id = #{userId}"""
    )
    fun findMember(@Param("sessionId") sessionId: Long, @Param("userId") userId: Long): CollabMember?

    @Select("""SELECT COUNT(*) FROM collab_member WHERE session_id = #{sessionId}""")
    fun countMembers(@Param("sessionId") sessionId: Long): Long

    // ---------- 贡献（只含分组统计） ----------

    @Select(
        """INSERT INTO collab_contribution (session_id, user_id, aggregate_json, group_count, suppressed_groups, approved)
           VALUES (#{sessionId}, #{userId}, #{aggregateJson}::jsonb, #{groupCount}, #{suppressedGroups}, #{approved})
           ON CONFLICT (session_id, user_id) DO UPDATE
           SET aggregate_json = EXCLUDED.aggregate_json, group_count = EXCLUDED.group_count,
               suppressed_groups = EXCLUDED.suppressed_groups, approved = EXCLUDED.approved
           RETURNING id"""
    )
    fun upsertContribution(
        @Param("sessionId") sessionId: Long,
        @Param("userId") userId: Long,
        @Param("aggregateJson") aggregateJson: String,
        @Param("groupCount") groupCount: Int,
        @Param("suppressedGroups") suppressedGroups: Int,
        @Param("approved") approved: Int,
    ): Long

    @Select(
        """SELECT c.id, c.session_id, c.user_id, c.aggregate_json::text AS aggregate_json,
                  c.group_count, c.suppressed_groups, c.approved,
                  to_char(c.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at, u.username AS user_name
           FROM collab_contribution c JOIN "user" u ON u.id = c.user_id
           WHERE c.session_id = #{sessionId} ORDER BY c.id"""
    )
    fun listContributions(@Param("sessionId") sessionId: Long): List<CollabContribution>
}
