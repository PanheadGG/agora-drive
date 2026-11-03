package com.pgigi.agentclouddisk.auth

import com.pgigi.agentclouddisk.common.ApiException
import com.pgigi.agentclouddisk.common.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsUtils
import org.springframework.web.servlet.HandlerInterceptor

@Component
class JwtInterceptor(private val jwtUtil: JwtUtil) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (CorsUtils.isPreFlightRequest(request)) return true
        val path = request.requestURI
        if (PUBLIC_PATHS.any { path == it }) return true

        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith("Bearer ")) throw UnauthorizedException()
        val claims = jwtUtil.parse(header.removePrefix("Bearer ").trim()) ?: throw UnauthorizedException()
        if (path.startsWith("/api/admin") && claims.role != ADMIN_ROLE) {
            throw ApiException(403, "需要管理员权限")
        }
        request.setAttribute("userId", claims.userId)
        request.setAttribute("username", claims.username)
        request.setAttribute("role", claims.role)
        return true
    }

    companion object {
        private const val ADMIN_ROLE = 1
        private val PUBLIC_PATHS = setOf("/api/auth/login", "/api/auth/register")
    }
}
