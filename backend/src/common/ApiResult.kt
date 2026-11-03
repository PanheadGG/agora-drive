package com.pgigi.agentclouddisk.common

data class ApiResult<T>(
    val code: Int,
    val msg: String,
    val data: T? = null,
) {
    companion object {
        fun <T> ok(data: T? = null): ApiResult<T> = ApiResult(0, "ok", data)
        fun fail(code: Int, msg: String): ApiResult<Any?> = ApiResult(code, msg, null)
    }
}
