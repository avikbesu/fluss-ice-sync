package com.flusssync.nlconfig.controller

import com.flusssync.nlconfig.dto.ErrorResponse
import com.flusssync.nlconfig.exception.ConfigNotFoundException
import com.flusssync.nlconfig.exception.ConfigStorageException
import com.flusssync.nlconfig.exception.ConfigValidationException
import com.flusssync.nlconfig.exception.CorruptConfigFileException
import com.flusssync.nlconfig.exception.DuplicateConfigNameException
import com.flusssync.nlconfig.exception.InvalidConfigIdException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Every error this service returns has this one JSON shape -- never a raw filesystem exception message or a stack trace. */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(InvalidConfigIdException::class)
    fun invalidId(e: InvalidConfigIdException) = respond(HttpStatus.BAD_REQUEST, "INVALID_CONFIG_ID", e.message!!)

    @ExceptionHandler(ConfigNotFoundException::class)
    fun notFound(e: ConfigNotFoundException) = respond(HttpStatus.NOT_FOUND, "CONFIG_NOT_FOUND", e.message!!)

    @ExceptionHandler(DuplicateConfigNameException::class)
    fun duplicateName(e: DuplicateConfigNameException) = respond(HttpStatus.CONFLICT, "CONFIG_NAME_CONFLICT", e.message!!)

    @ExceptionHandler(ConfigValidationException::class)
    fun validationFailed(e: ConfigValidationException) =
        respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.message!!, e.fieldErrors)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun beanValidationFailed(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val fieldErrors = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request body failed validation.", fieldErrors)
    }

    @ExceptionHandler(CorruptConfigFileException::class)
    fun corruptFile(e: CorruptConfigFileException): ResponseEntity<ErrorResponse> {
        log.error("Corrupt config file for id {}", e.id, e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "CONFIG_FILE_CORRUPT", "This config's file exists but could not be read.")
    }

    @ExceptionHandler(ConfigStorageException::class)
    fun storageFailure(e: ConfigStorageException): ResponseEntity<ErrorResponse> {
        log.error("Config storage failure", e)
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_ERROR", e.message ?: "Config storage is currently unavailable.")
    }

    @ExceptionHandler(Exception::class)
    fun unhandled(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.")
    }

    private fun respond(status: HttpStatus, code: String, message: String, fieldErrors: Map<String, String> = emptyMap()) =
        ResponseEntity.status(status).body(ErrorResponse(code, message, fieldErrors))
}
