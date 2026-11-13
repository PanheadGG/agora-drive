package com.pgigi.agentclouddisk.mapper.market

import com.pgigi.agentclouddisk.agent.market.DataProduct
import com.pgigi.agentclouddisk.agent.market.ProductBuildItem
import com.pgigi.agentclouddisk.agent.market.ProductBuildTask
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface DataProductMapper {

    @Select(
        """INSERT INTO data_product (owner_id, title, template_key, template_label, pii_status, status, source_kind, ai_enabled)
           VALUES (#{ownerId}, #{title}, #{templateKey}, #{templateLabel}, 'pending', 0, #{sourceKind}, #{aiEnabled})
           RETURNING id"""
    )
    fun insertShell(
        @Param("ownerId") ownerId: Long,
        @Param("title") title: String,
        @Param("templateKey") templateKey: String,
        @Param("templateLabel") templateLabel: String,
        @Param("sourceKind") sourceKind: String,
        @Param("aiEnabled") aiEnabled: Int,
    ): Long

    @Update(
        """UPDATE data_product SET schema_json = #{schemaJson}::jsonb, row_count = #{rowCount},
                  contributing_files = #{contributingFiles}, coverage_start = #{coverageStart,jdbcType=VARCHAR},
                  coverage_end = #{coverageEnd,jdbcType=VARCHAR}, quality_json = #{qualityJson}::jsonb,
                  tags_json = #{tagsJson}::jsonb, sample_json = #{sampleJson}::jsonb,
                  build_receipt = #{buildReceipt}::jsonb, pii_status = #{piiStatus}, status = #{status},
                  updated_at = CURRENT_TIMESTAMP
           WHERE id = #{id}"""
    )
    fun updateBuilt(
        @Param("id") id: Long,
        @Param("schemaJson") schemaJson: String,
        @Param("rowCount") rowCount: Long,
        @Param("contributingFiles") contributingFiles: Int,
        @Param("coverageStart") coverageStart: String?,
        @Param("coverageEnd") coverageEnd: String?,
        @Param("qualityJson") qualityJson: String,
        @Param("tagsJson") tagsJson: String,
        @Param("sampleJson") sampleJson: String,
        @Param("buildReceipt") buildReceipt: String,
        @Param("piiStatus") piiStatus: String,
        @Param("status") status: Int,
    ): Int

    @Update("""UPDATE data_product SET status = #{status}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    @Delete("""DELETE FROM data_product WHERE id = #{id}""")
    fun delete(@Param("id") id: Long): Int

    @Select(
        """SELECT p.id, p.owner_id, p.title, p.template_key, p.template_label,
                  p.schema_json::text AS schema_json, p.row_count, p.contributing_files,
                  p.coverage_start, p.coverage_end, p.quality_json::text AS quality_json,
                  p.tags_json::text AS tags_json, p.sample_json::text AS sample_json,
                  p.build_receipt::text AS build_receipt, p.pii_status, p.status, p.source_kind, p.ai_enabled,
                  to_char(p.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(p.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  u.username AS owner_name
           FROM data_product p JOIN "user" u ON u.id = p.owner_id
           WHERE p.id = #{id}"""
    )
    fun findById(@Param("id") id: Long): DataProduct?

    @Select(
        """SELECT p.id, p.owner_id, p.title, p.template_key, p.template_label,
                  p.schema_json::text AS schema_json, p.row_count, p.contributing_files,
                  p.coverage_start, p.coverage_end, p.quality_json::text AS quality_json,
                  p.tags_json::text AS tags_json, p.sample_json::text AS sample_json,
                  p.build_receipt::text AS build_receipt, p.pii_status, p.status, p.source_kind, p.ai_enabled,
                  to_char(p.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(p.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  u.username AS owner_name
           FROM data_product p JOIN "user" u ON u.id = p.owner_id
           WHERE p.owner_id = #{ownerId} ORDER BY p.id DESC"""
    )
    fun listByOwner(@Param("ownerId") ownerId: Long): List<DataProduct>

    @Select(
        """SELECT p.id, p.owner_id, p.title, p.template_key, p.template_label,
                  p.schema_json::text AS schema_json, p.row_count, p.contributing_files,
                  p.coverage_start, p.coverage_end, p.quality_json::text AS quality_json,
                  p.tags_json::text AS tags_json, p.sample_json::text AS sample_json,
                  p.build_receipt::text AS build_receipt, p.pii_status, p.status, p.source_kind, p.ai_enabled,
                  to_char(p.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(p.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  u.username AS owner_name
           FROM data_product p JOIN "user" u ON u.id = p.owner_id
           WHERE p.status = #{status} ORDER BY p.id DESC"""
    )
    fun listByStatus(@Param("status") status: Int): List<DataProduct>

    @Select("""SELECT COUNT(*) FROM data_product""")
    fun countAll(): Long

    @Select(
        """INSERT INTO product_build_task (product_id, user_id, scope_json, task_status, ai_enabled, model_name)
           VALUES (#{productId}, #{userId}, #{scopeJson}::jsonb, 0, #{aiEnabled}, #{modelName,jdbcType=VARCHAR})
           RETURNING id"""
    )
    fun insertTask(
        @Param("productId") productId: Long,
        @Param("userId") userId: Long,
        @Param("scopeJson") scopeJson: String,
        @Param("aiEnabled") aiEnabled: Int,
        @Param("modelName") modelName: String?,
    ): Long

    @Update(
        """UPDATE product_build_task SET task_status = #{status}, total_file_count = #{total},
                  success_parse_count = #{success}, fail_parse_count = #{fail},
                  finished_at = CASE WHEN #{status} IN (2, 3) THEN CURRENT_TIMESTAMP ELSE finished_at END
           WHERE id = #{id}"""
    )
    fun updateTask(
        @Param("id") id: Long,
        @Param("status") status: Int,
        @Param("total") total: Int,
        @Param("success") success: Int,
        @Param("fail") fail: Int,
    ): Int

    @Select(
        """SELECT t.id, t.product_id, t.user_id, t.scope_json::text AS scope_json,
                  t.task_status, t.total_file_count, t.success_parse_count, t.fail_parse_count, t.ai_enabled,
                  t.model_name, to_char(t.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(t.finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at
           FROM product_build_task t WHERE t.id = #{id}"""
    )
    fun findTask(@Param("id") id: Long): ProductBuildTask?

    @Select(
        """SELECT t.id, t.product_id, t.user_id, t.scope_json::text AS scope_json,
                  t.task_status, t.total_file_count, t.success_parse_count, t.fail_parse_count, t.ai_enabled,
                  t.model_name, to_char(t.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(t.finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at
           FROM product_build_task t WHERE t.product_id = #{productId} ORDER BY t.id DESC"""
    )
    fun listTasks(@Param("productId") productId: Long): List<ProductBuildTask>

    @Select(
        """INSERT INTO product_build_item (task_id, user_file_id, file_sha256, file_name, parse_method,
                  rows_contributed, status, risk_tag, note)
           VALUES (#{taskId}, #{userFileId}, #{fileSha256,jdbcType=VARCHAR}, #{fileName,jdbcType=VARCHAR},
                   #{parseMethod,jdbcType=VARCHAR}, #{rowsContributed}, #{status},
                   #{riskTag,jdbcType=VARCHAR}, #{note,jdbcType=VARCHAR})
           RETURNING id"""
    )
    fun insertItem(
        @Param("taskId") taskId: Long,
        @Param("userFileId") userFileId: Long,
        @Param("fileSha256") fileSha256: String?,
        @Param("fileName") fileName: String?,
        @Param("parseMethod") parseMethod: String?,
        @Param("rowsContributed") rowsContributed: Int,
        @Param("status") status: Int,
        @Param("riskTag") riskTag: String?,
        @Param("note") note: String?,
    ): Long

    @Select(
        """SELECT id, task_id, user_file_id, file_sha256, file_name, parse_method, rows_contributed,
                  status, risk_tag, note, to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
           FROM product_build_item WHERE task_id = #{taskId} ORDER BY id"""
    )
    fun listItems(@Param("taskId") taskId: Long): List<ProductBuildItem>
}
