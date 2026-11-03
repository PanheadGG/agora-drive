package com.pgigi.agentclouddisk.common

open class ApiException(val code: Int, override val message: String) : RuntimeException(message)

class UnauthorizedException(message: String = "未登录或登录已过期") : ApiException(401, message)

class QuotaExceededException(message: String = "存储空间不足") : ApiException(400, message)
