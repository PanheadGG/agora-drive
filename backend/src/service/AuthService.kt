package com.pgigi.agentclouddisk.service

import com.pgigi.agentclouddisk.auth.CurrentUser
import com.pgigi.agentclouddisk.auth.JwtUtil
import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.mapper.UserMapper
import com.pgigi.agentclouddisk.model.LoginResponse
import com.pgigi.agentclouddisk.model.UserDto
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userMapper: UserMapper,
    private val passwordEncoder: BCryptPasswordEncoder,
    private val jwtUtil: JwtUtil,
) {

    fun register(username: String, password: String): LoginResponse {
        val name = username.trim()
        if (name.length !in 3..32) throw ApiException(400, "用户名长度需为 3-32 个字符")
        if (password.length !in 6..64) throw ApiException(400, "密码长度需为 6-64 个字符")
        if (userMapper.findByUsername(name) != null) throw ApiException(400, "用户名已被占用")
        val id = userMapper.insert(name, passwordEncoder.encode(password)!!, 0)
        val user = userMapper.findById(id) ?: throw ApiException(500, "注册失败")
        return LoginResponse(jwtUtil.create(user.id ?: 0, user.username, user.role), UserDto.of(user))
    }

    fun login(username: String, password: String): LoginResponse {
        val user = userMapper.findByUsername(username.trim()) ?: throw ApiException(400, "用户名或密码错误")
        if (!passwordEncoder.matches(password, user.password)) throw ApiException(400, "用户名或密码错误")
        if (user.status != 1) throw ApiException(403, "账号已被禁用，请联系管理员")
        return LoginResponse(jwtUtil.create(user.id ?: 0, user.username, user.role), UserDto.of(user))
    }

    fun me(): UserDto {
        val user = userMapper.findById(CurrentUser.id()) ?: throw ApiException(401, "用户不存在")
        return UserDto.of(user)
    }
}
