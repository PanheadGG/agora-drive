package com.pgigi.agentclouddisk.controller

import com.pgigi.agentclouddisk.common.ApiResult
import com.pgigi.agentclouddisk.model.LoginResponse
import com.pgigi.agentclouddisk.model.UserDto
import com.pgigi.agentclouddisk.service.AuthService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class Credentials(val username: String = "", val password: String = "")

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    fun register(@RequestBody body: Credentials): ApiResult<LoginResponse> =
        ApiResult.ok(authService.register(body.username, body.password))

    @PostMapping("/login")
    fun login(@RequestBody body: Credentials): ApiResult<LoginResponse> =
        ApiResult.ok(authService.login(body.username, body.password))

    @GetMapping("/me")
    fun me(): ApiResult<UserDto> = ApiResult.ok(authService.me())
}
