package com.pgigi.agentclouddisk.mapper

import com.pgigi.agentclouddisk.model.UploadTask
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface UploadTaskMapper {

    @Select(
        """INSERT INTO upload_task (user_id, task_id, file_name, total_size, total_chunk, expire_at)
           VALUES (#{userId}, #{taskId}, #{fileName}, #{totalSize}, #{totalChunk},
                   CURRENT_TIMESTAMP + INTERVAL '24 hours')
           RETURNING id"""
    )
    fun insert(
        @Param("userId") userId: Long,
        @Param("taskId") taskId: String,
        @Param("fileName") fileName: String,
        @Param("totalSize") totalSize: Long,
        @Param("totalChunk") totalChunk: Int,
    ): Long

    @Select(
        """SELECT id, user_id, task_id, file_name, total_size, total_chunk, sha256, finished_chunk, status
           FROM upload_task WHERE task_id = #{taskId}"""
    )
    fun findByTaskId(@Param("taskId") taskId: String): UploadTask?

    @Select(
        """SELECT (expire_at > CURRENT_TIMESTAMP) FROM upload_task WHERE task_id = #{taskId}"""
    )
    fun isAlive(@Param("taskId") taskId: String): Boolean?

    @Update(
        """UPDATE upload_task SET finished_chunk = #{finishedChunk}, status = #{status},
                  sha256 = #{sha256,jdbcType=VARCHAR}, updated_at = CURRENT_TIMESTAMP
           WHERE task_id = #{taskId}"""
    )
    fun updateProgress(
        @Param("taskId") taskId: String,
        @Param("finishedChunk") finishedChunk: Int,
        @Param("status") status: Int,
        @Param("sha256") sha256: String?,
    ): Int

    @Select("""SELECT COUNT(*) FROM upload_task WHERE status = #{status}""")
    fun countByStatus(@Param("status") status: Int): Long
}
