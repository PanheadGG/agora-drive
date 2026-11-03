package com.pgigi.agentclouddisk.mapper

import com.pgigi.agentclouddisk.model.User
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface UserMapper {

    @Select(
        """SELECT id, username, password, total_quota, used_size, status, role,
                  to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
           FROM "user" WHERE username = #{username}"""
    )
    fun findByUsername(@Param("username") username: String): User?

    @Select(
        """SELECT id, username, password, total_quota, used_size, status, role,
                  to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
           FROM "user" WHERE id = #{id}"""
    )
    fun findById(@Param("id") id: Long): User?

    @Select("""INSERT INTO "user" (username, password, role) VALUES (#{username}, #{password}, #{role}) RETURNING id""")
    fun insert(
        @Param("username") username: String,
        @Param("password") password: String,
        @Param("role") role: Int,
    ): Long

    /** 原子扣减/增加已用空间，返回 0 表示超出配额 */
    @Update(
        """UPDATE "user" SET used_size = used_size + #{delta}, updated_at = CURRENT_TIMESTAMP
           WHERE id = #{id} AND used_size + #{delta} <= total_quota"""
    )
    fun addUsedSize(@Param("id") id: Long, @Param("delta") delta: Long): Int

    @Update("""UPDATE "user" SET used_size = CASE WHEN used_size >= #{delta} THEN used_size - #{delta} ELSE 0 END,
                      updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun reduceUsedSize(@Param("id") id: Long, @Param("delta") delta: Long): Int

    @Select(
        """SELECT id, username, total_quota, used_size, status, role,
                  to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
           FROM "user"
           WHERE (#{keyword} = '' OR username ILIKE CONCAT('%', #{keyword}, '%'))
           ORDER BY id"""
    )
    fun list(@Param("keyword") keyword: String): List<User>

    @Update("""UPDATE "user" SET status = #{status}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun updateStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    @Update("""UPDATE "user" SET role = #{role}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun updateRole(@Param("id") id: Long, @Param("role") role: Int): Int

    @Update("""UPDATE "user" SET total_quota = #{quota}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun updateQuota(@Param("id") id: Long, @Param("quota") quota: Long): Int

    @Select("""SELECT COUNT(*) FROM "user" """)
    fun countAll(): Long

    @Select("""SELECT COALESCE(SUM(used_size), 0) FROM "user" """)
    fun sumUsedSize(): Long
}
