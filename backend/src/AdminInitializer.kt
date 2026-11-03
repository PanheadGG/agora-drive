package com.pgigi.agentclouddisk

import com.pgigi.agentclouddisk.mapper.UserMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

/** 演示用账号：admin/admin123（管理员）、demo/demo1234（普通用户） */
@Component
class AdminInitializer(
    private val userMapper: UserMapper,
    private val encoder: BCryptPasswordEncoder,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(AdminInitializer::class.java)

    override fun run(args: ApplicationArguments) {
        seed("admin", "admin123", 1)
        seed("demo", "demo1234", 0)
    }

    private fun seed(username: String, rawPassword: String, role: Int) {
        if (userMapper.findByUsername(username) != null) return
        userMapper.insert(username, encoder.encode(rawPassword)!!, role)
        log.info("seeded account {} (role={})", username, role)
    }
}
