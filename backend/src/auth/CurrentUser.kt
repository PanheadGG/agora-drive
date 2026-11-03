package com.pgigi.agentclouddisk.auth

import com.pgigi.agentclouddisk.common.UnauthorizedException
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder

object CurrentUser {

    fun id(): Long = attribute("userId") as? Long ?: throw UnauthorizedException()

    fun role(): Int = attribute("role") as? Int ?: 0

    private fun attribute(name: String): Any? =
        RequestContextHolder.getRequestAttributes()?.getAttribute(name, RequestAttributes.SCOPE_REQUEST)
}
