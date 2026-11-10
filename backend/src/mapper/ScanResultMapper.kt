package com.pgigi.agentclouddisk.mapper

import com.pgigi.agentclouddisk.model.ScanResult
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface ScanResultMapper {

    @Select(
        """INSERT INTO agent_scan_result (scan_task_id, user_file_id, match_level, parse_status, parse_method,
                  parsed_summary, evidence_text, risk_tag, risk_desc, judge_source, judge_reason,
                  preview_text, preview_kind)
           VALUES (#{scanTaskId}, #{userFileId}, #{matchLevel}, #{parseStatus}, #{parseMethod,jdbcType=VARCHAR},
                   #{parsedSummary,jdbcType=VARCHAR}, #{evidenceText,jdbcType=VARCHAR},
                   #{riskTag,jdbcType=VARCHAR}, #{riskDesc,jdbcType=VARCHAR},
                   #{judgeSource,jdbcType=VARCHAR}, #{judgeReason,jdbcType=VARCHAR},
                   #{previewText,jdbcType=VARCHAR}, #{previewKind,jdbcType=VARCHAR})
           RETURNING id"""
    )
    fun insert(
        @Param("scanTaskId") scanTaskId: Long,
        @Param("userFileId") userFileId: Long,
        @Param("matchLevel") matchLevel: Int,
        @Param("parseStatus") parseStatus: Int,
        @Param("parseMethod") parseMethod: String?,
        @Param("parsedSummary") parsedSummary: String?,
        @Param("evidenceText") evidenceText: String?,
        @Param("riskTag") riskTag: String?,
        @Param("riskDesc") riskDesc: String?,
        @Param("judgeSource") judgeSource: String = "rule",
        @Param("judgeReason") judgeReason: String? = null,
        @Param("previewText") previewText: String? = null,
        @Param("previewKind") previewKind: String? = null,
    ): Long

    @Select(
        """SELECT r.id, r.scan_task_id, r.user_file_id, r.match_level, r.parse_status, r.parse_method,
                  r.parsed_summary, r.evidence_text, r.risk_tag, r.risk_desc, r.user_review, r.review_note,
                  r.judge_source, r.judge_reason, r.preview_text, r.preview_kind,
                  to_char(r.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  uf.file_name, COALESCE(m.total_size, 0) AS file_size
           FROM agent_scan_result r
           JOIN user_file uf ON uf.id = r.user_file_id
           LEFT JOIN file_meta m ON m.id = uf.meta_id
           WHERE r.scan_task_id = #{taskId}
           ORDER BY r.match_level DESC, r.id"""
    )
    fun listByTask(@Param("taskId") taskId: Long): List<ScanResult>

    @Select(
        """SELECT r.id, r.scan_task_id, r.user_file_id, r.match_level, r.parse_status, r.parse_method,
                  r.parsed_summary, r.evidence_text, r.risk_tag, r.risk_desc, r.user_review, r.review_note,
                  r.judge_source, r.judge_reason, r.preview_text, r.preview_kind,
                  to_char(r.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  uf.file_name, COALESCE(m.total_size, 0) AS file_size
           FROM agent_scan_result r
           JOIN user_file uf ON uf.id = r.user_file_id
           LEFT JOIN file_meta m ON m.id = uf.meta_id
           WHERE r.id = #{id}"""
    )
    fun findById(@Param("id") id: Long): ScanResult?

    @Update(
        """UPDATE agent_scan_result SET user_review = #{review}, review_note = #{note,jdbcType=VARCHAR}
           WHERE id = #{id}"""
    )
    fun updateReview(@Param("id") id: Long, @Param("review") review: Int, @Param("note") note: String?): Int

    @Select("""SELECT COUNT(*) FROM agent_scan_result WHERE user_review = #{review}""")
    fun countByReview(@Param("review") review: Int): Long

    @Select("""SELECT COUNT(*) FROM agent_scan_result r JOIN agent_scan_task t ON t.id = r.scan_task_id
               WHERE t.bounty_id = #{bountyId} AND r.match_level = 3""")
    fun countHighMatchByBounty(@Param("bountyId") bountyId: Long): Long
}
