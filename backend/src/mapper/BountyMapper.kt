package com.pgigi.agentclouddisk.mapper

import com.pgigi.agentclouddisk.model.Bounty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface BountyMapper {

    @Select(
        """INSERT INTO data_bounty (publisher_user_id, title, raw_desc, structured_json, status, structured_by, structured_model)
           VALUES (#{publisherUserId}, #{title}, #{rawDesc}, #{structuredJson}::jsonb, #{status}, #{structuredBy,jdbcType=VARCHAR}, #{structuredModel,jdbcType=VARCHAR})
           RETURNING id"""
    )
    fun insert(
        @Param("publisherUserId") publisherUserId: Long,
        @Param("title") title: String,
        @Param("rawDesc") rawDesc: String,
        @Param("structuredJson") structuredJson: String,
        @Param("status") status: Int,
        @Param("structuredBy") structuredBy: String = "rule",
        @Param("structuredModel") structuredModel: String? = null,
    ): Long

    @Select(
        """SELECT b.id, b.publisher_user_id, b.title, b.raw_desc, b.structured_json::text AS structured_json, b.status,
                  to_char(b.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at, u.username AS publisher_name,
                  b.structured_by, b.structured_model
           FROM data_bounty b JOIN "user" u ON u.id = b.publisher_user_id
           WHERE b.id = #{id}"""
    )
    fun findById(@Param("id") id: Long): Bounty?


    @Select(
        """SELECT b.id, b.publisher_user_id, b.title, b.raw_desc, b.structured_json::text AS structured_json, b.status,
                  to_char(b.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at, u.username AS publisher_name,
                  b.structured_by, b.structured_model
           FROM data_bounty b JOIN "user" u ON u.id = b.publisher_user_id
           WHERE (#{status,jdbcType=INTEGER} IS NULL OR b.status = #{status,jdbcType=INTEGER})
           ORDER BY b.status ASC, b.id DESC"""
    )
    fun list(@Param("status") status: Int?): List<Bounty>

    @Select(
        """SELECT b.id, b.publisher_user_id, b.title, b.raw_desc, b.structured_json::text AS structured_json, b.status,
                  to_char(b.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at, u.username AS publisher_name,
                  b.structured_by, b.structured_model
           FROM data_bounty b JOIN "user" u ON u.id = b.publisher_user_id
           WHERE b.publisher_user_id = #{userId}
           ORDER BY b.id DESC"""
    )
    fun listByPublisher(@Param("userId") userId: Long): List<Bounty>

    @Update("""UPDATE data_bounty SET status = #{status}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    @Select("""SELECT COUNT(*) FROM data_bounty""")
    fun countAll(): Long

    @Select("""SELECT COUNT(*) FROM data_bounty WHERE status = 1""")
    fun countActive(): Long
}
