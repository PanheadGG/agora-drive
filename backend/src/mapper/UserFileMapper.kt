package com.pgigi.agentclouddisk.mapper

import com.pgigi.agentclouddisk.model.FileRow
import com.pgigi.agentclouddisk.model.FolderRow
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface UserFileMapper {

    @Select(
        """SELECT uf.id, uf.parent_id, uf.meta_id, uf.file_name, uf.is_folder,
                  COALESCE(m.total_size, 0) AS total_size, m.sha256,
                  to_char(uf.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(uf.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at
           FROM user_file uf LEFT JOIN file_meta m ON m.id = uf.meta_id
           WHERE uf.user_id = #{userId} AND uf.is_recycle = 0
             AND uf.parent_id IS NOT DISTINCT FROM #{parentId,jdbcType=BIGINT}
             AND (#{keyword} = '' OR uf.file_name ILIKE CONCAT('%', #{keyword}, '%'))
           ORDER BY uf.is_folder DESC, uf.file_name"""
    )
    fun listDir(
        @Param("userId") userId: Long,
        @Param("parentId") parentId: Long?,
        @Param("keyword") keyword: String,
    ): List<FileRow>

    @Select(
        """SELECT uf.id, uf.parent_id, uf.meta_id, uf.file_name, uf.is_folder, uf.is_recycle,
                  COALESCE(m.total_size, 0) AS total_size, m.sha256,
                  to_char(uf.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(uf.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at
           FROM user_file uf LEFT JOIN file_meta m ON m.id = uf.meta_id
           WHERE uf.id = #{id} AND uf.user_id = #{userId}"""
    )
    fun findRow(@Param("id") id: Long, @Param("userId") userId: Long): FileRow?

    @Select(
        """SELECT uf.id, uf.parent_id, uf.meta_id, uf.file_name, uf.is_folder,
                  COALESCE(m.total_size, 0) AS total_size, m.sha256,
                  to_char(uf.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(uf.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at
           FROM user_file uf LEFT JOIN file_meta m ON m.id = uf.meta_id
           LEFT JOIN user_file p ON p.id = uf.parent_id
           WHERE uf.user_id = #{userId} AND uf.is_recycle = 1
             AND (uf.parent_id IS NULL OR p.is_recycle = 0)
           ORDER BY uf.updated_at DESC"""
    )
    fun listRecycle(@Param("userId") userId: Long): List<FileRow>

    @Select(
        """SELECT COUNT(*) FROM user_file
           WHERE user_id = #{userId} AND is_recycle = 0 AND file_name = #{fileName}
             AND parent_id IS NOT DISTINCT FROM #{parentId,jdbcType=BIGINT}"""
    )
    fun countSameName(
        @Param("userId") userId: Long,
        @Param("parentId") parentId: Long?,
        @Param("fileName") fileName: String,
    ): Long

    @Select(
        """INSERT INTO user_file (user_id, parent_id, meta_id, file_name, is_folder)
           VALUES (#{userId}, #{parentId,jdbcType=BIGINT}, #{metaId,jdbcType=BIGINT}, #{fileName}, #{isFolder})
           RETURNING id"""
    )
    fun insert(
        @Param("userId") userId: Long,
        @Param("parentId") parentId: Long?,
        @Param("metaId") metaId: Long?,
        @Param("fileName") fileName: String,
        @Param("isFolder") isFolder: Int,
    ): Long

    @Update("""UPDATE user_file SET file_name = #{fileName}, updated_at = CURRENT_TIMESTAMP
               WHERE id = #{id} AND user_id = #{userId}""")
    fun rename(@Param("id") id: Long, @Param("userId") userId: Long, @Param("fileName") fileName: String): Int

    @Update(
        """WITH RECURSIVE sub AS (
               SELECT id FROM user_file WHERE id = #{id} AND user_id = #{userId}
               UNION ALL
               SELECT c.id FROM user_file c JOIN sub s ON c.parent_id = s.id
           )
           UPDATE user_file SET is_recycle = 1, updated_at = CURRENT_TIMESTAMP
           WHERE id IN (SELECT id FROM sub)"""
    )
    fun markRecycle(@Param("id") id: Long, @Param("userId") userId: Long): Int

    @Update(
        """WITH RECURSIVE sub AS (
               SELECT id FROM user_file WHERE id = #{id} AND user_id = #{userId}
               UNION ALL
               SELECT c.id FROM user_file c JOIN sub s ON c.parent_id = s.id
           )
           UPDATE user_file SET is_recycle = 0, updated_at = CURRENT_TIMESTAMP
           WHERE id IN (SELECT id FROM sub)"""
    )
    fun restoreTree(@Param("id") id: Long, @Param("userId") userId: Long): Int

    @Select(
        """WITH RECURSIVE sub AS (
               SELECT id, meta_id FROM user_file WHERE id = #{id} AND user_id = #{userId}
               UNION ALL
               SELECT c.id, c.meta_id FROM user_file c JOIN sub s ON c.parent_id = s.id
           )
           SELECT COALESCE(SUM(m.total_size), 0) FROM sub s JOIN file_meta m ON m.id = s.meta_id"""
    )
    fun subtreeSize(@Param("id") id: Long, @Param("userId") userId: Long): Long

    @Delete(
        """WITH RECURSIVE sub AS (
               SELECT id FROM user_file WHERE id = #{id} AND user_id = #{userId}
               UNION ALL
               SELECT c.id FROM user_file c JOIN sub s ON c.parent_id = s.id
           )
           DELETE FROM user_file WHERE id IN (SELECT id FROM sub)"""
    )
    fun deleteTree(@Param("id") id: Long, @Param("userId") userId: Long): Int

    @Select(
        """SELECT COALESCE(SUM(m.total_size), 0) FROM user_file uf
           JOIN file_meta m ON m.id = uf.meta_id
           WHERE uf.user_id = #{userId} AND uf.is_recycle = 1"""
    )
    fun recycleSize(@Param("userId") userId: Long): Long

    @Delete("""DELETE FROM user_file WHERE user_id = #{userId} AND is_recycle = 1""")
    fun deleteAllRecycle(@Param("userId") userId: Long): Int

    @Select("""SELECT COUNT(*) FROM user_file WHERE is_folder = #{isFolder} AND is_recycle = #{isRecycle}""")
    fun countByType(@Param("isFolder") isFolder: Int, @Param("isRecycle") isRecycle: Int): Long

    @Select(
        """WITH RECURSIVE sub AS (
               SELECT id, parent_id, meta_id, file_name, is_folder
               FROM user_file WHERE id = #{rootId} AND user_id = #{userId} AND is_recycle = 0
               UNION ALL
               SELECT c.id, c.parent_id, c.meta_id, c.file_name, c.is_folder
               FROM user_file c JOIN sub s ON c.parent_id = s.id
               WHERE c.is_recycle = 0
           )
           SELECT s.id, s.parent_id, s.meta_id, s.file_name, s.is_folder,
                  COALESCE(m.total_size, 0) AS total_size, m.sha256
           FROM sub s LEFT JOIN file_meta m ON m.id = s.meta_id"""
    )
    fun expandRows(@Param("rootId") rootId: Long, @Param("userId") userId: Long): List<FileRow>

    @Select(
        """SELECT uf.id, uf.parent_id, uf.file_name,
                  (SELECT COUNT(*) FROM user_file c WHERE c.parent_id = uf.id AND c.is_recycle = 0) AS child_count
           FROM user_file uf
           WHERE uf.user_id = #{userId} AND uf.is_folder = 1 AND uf.is_recycle = 0
           ORDER BY uf.parent_id NULLS FIRST, uf.file_name"""
    )
    fun listFolders(@Param("userId") userId: Long): List<FolderRow>

    @Select("""SELECT COUNT(*) FROM user_file WHERE is_recycle = 1""")
    fun countRecycle(): Long

    @Select("""SELECT COUNT(*) FROM user_file WHERE user_id = #{userId} AND is_recycle = 0""")
    fun countByUser(@Param("userId") userId: Long): Long
}
