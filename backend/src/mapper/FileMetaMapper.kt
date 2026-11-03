package com.pgigi.agentclouddisk.mapper

import com.pgigi.agentclouddisk.model.FileMeta
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

@Mapper
interface FileMetaMapper {

    @Select("""SELECT id, sha256, total_size FROM file_meta WHERE sha256 = #{sha256}""")
    fun findBySha256(@Param("sha256") sha256: String): FileMeta?

    @Select("""SELECT id, sha256, total_size FROM file_meta WHERE id = #{id}""")
    fun findById(@Param("id") id: Long): FileMeta?

    /** 命中唯一键时返回 null，由调用方回读已有记录（秒传） */
    @Select(
        """INSERT INTO file_meta (sha256, total_size) VALUES (#{sha256}, #{totalSize})
           ON CONFLICT (sha256) DO NOTHING RETURNING id"""
    )
    fun insertIfAbsent(@Param("sha256") sha256: String, @Param("totalSize") totalSize: Long): Long?

    @Select("""SELECT COUNT(*) FROM file_meta""")
    fun countAll(): Long
}
