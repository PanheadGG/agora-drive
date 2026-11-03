package com.pgigi.agentclouddisk.common

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ApiResult<Any?>> =
        ResponseEntity.status(e.code).body(ApiResult.fail(e.code, e.message))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDuplicate(e: DataIntegrityViolationException): ResponseEntity<ApiResult<Any?>> {
        log.warn("data integrity: {}", e.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResult.fail(400, "同名文件已存在或数据冲突"))
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleTooLarge(e: MaxUploadSizeExceededException): ResponseEntity<ApiResult<Any?>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResult.fail(400, "文件超过单次上传上限"))

    @ExceptionHandler(Exception::class)
    fun handleOther(e: Exception): ResponseEntity<ApiResult<Any?>> {
        log.error("unexpected error", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResult.fail(500, e.message ?: "服务器内部错误"))
    }
}
