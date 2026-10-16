package com.ject.global.error

import com.ject.external.storage.ObjectStorageException
import com.ject.external.storage.ObjectStorageFailureReason
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.ConstraintViolationException
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.validation.method.ParameterErrors
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(exception: BusinessException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(exception.errorCode.status).body(ErrorResponse.from(exception.errorCode))

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(exception: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        val errors =
            exception.constraintViolations.map {
                FieldError(field = it.propertyPath.toString(), reason = it.message)
            }
        return ResponseEntity.badRequest().body(ErrorResponse.from(CommonErrorCode.INVALID_REQUEST, errors))
    }

    @ExceptionHandler(ObjectStorageException::class)
    fun handleObjectStorageException(exception: ObjectStorageException): ResponseEntity<ErrorResponse> {
        val errorCode =
            when (exception.reason) {
                ObjectStorageFailureReason.UNAVAILABLE -> CommonErrorCode.SERVICE_UNAVAILABLE

                ObjectStorageFailureReason.CONFIGURATION,
                ObjectStorageFailureReason.NOT_FOUND,
                ObjectStorageFailureReason.UNKNOWN,
                -> CommonErrorCode.INTERNAL_SERVER_ERROR
            }
        val rootCauseType = generateSequence(exception.cause) { it.cause }.lastOrNull()?.javaClass?.name
        log.error {
            "event=object_storage_failed operation=${exception.operation} reason=${exception.reason} " +
                "storageStatus=${exception.statusCode} serviceCode=${exception.serviceCode} " +
                "storageRequestId=${exception.requestId} rootCauseType=$rootCauseType"
        }
        return ResponseEntity.status(errorCode.status).body(ErrorResponse.from(errorCode))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(exception: Exception): ResponseEntity<ErrorResponse> {
        log.error(exception) { "event=unexpected_request_error" }
        return ResponseEntity.internalServerError().body(ErrorResponse.from(CommonErrorCode.INTERNAL_SERVER_ERROR))
    }

    override fun handleMethodArgumentNotValid(
        exception: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors =
            exception.bindingResult.fieldErrors.map {
                FieldError(
                    field = it.field,
                    reason = if (it.isBindingFailure) "허용되지 않는 값입니다." else it.defaultMessage ?: "유효하지 않은 값입니다.",
                )
            }
        return handleExceptionInternal(exception, ErrorResponse.from(CommonErrorCode.INVALID_REQUEST, errors), headers, status, request)
    }

    override fun handleHandlerMethodValidationException(
        exception: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if (exception.isForReturnValue) {
            return handleExceptionInternal(exception, null, headers, status, request)
        }
        val errors =
            exception.parameterValidationResults.flatMap { result ->
                if (result is ParameterErrors) {
                    result.fieldErrors.map {
                        FieldError(field = it.field, reason = it.defaultMessage ?: "유효하지 않은 값입니다.")
                    }
                } else {
                    val field = result.methodParameter.parameterName
                    if (field == null) {
                        emptyList()
                    } else {
                        result.resolvableErrors.map { FieldError(field = field, reason = it.defaultMessage ?: "유효하지 않은 값입니다.") }
                    }
                }
            }
        return handleExceptionInternal(exception, ErrorResponse.from(CommonErrorCode.INVALID_REQUEST, errors), headers, status, request)
    }

    override fun handleTypeMismatch(
        exception: TypeMismatchException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val field = if (exception is MethodArgumentTypeMismatchException) exception.name else exception.propertyName
        val errors = field?.let { listOf(FieldError(field = it, reason = "허용되지 않는 값입니다.")) }
        return handleExceptionInternal(exception, ErrorResponse.from(CommonErrorCode.INVALID_REQUEST, errors), headers, status, request)
    }

    override fun handleMissingServletRequestParameter(
        exception: MissingServletRequestParameterException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        handleExceptionInternal(
            exception,
            ErrorResponse.from(CommonErrorCode.INVALID_REQUEST, listOf(FieldError(field = exception.parameterName, reason = "필수 값입니다."))),
            headers,
            status,
            request,
        )

    override fun handleExceptionInternal(
        exception: Exception,
        body: Any?,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errorCode =
            when (status.value()) {
                400 -> CommonErrorCode.INVALID_REQUEST
                401 -> CommonErrorCode.UNAUTHORIZED
                403 -> CommonErrorCode.FORBIDDEN
                404 -> CommonErrorCode.NOT_FOUND
                405 -> CommonErrorCode.METHOD_NOT_ALLOWED
                406 -> CommonErrorCode.NOT_ACCEPTABLE
                409 -> CommonErrorCode.CONFLICT
                413 -> CommonErrorCode.PAYLOAD_TOO_LARGE
                415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE
                503 -> CommonErrorCode.SERVICE_UNAVAILABLE
                else -> CommonErrorCode.INTERNAL_SERVER_ERROR
            }
        if (status.is5xxServerError) {
            log.error(exception) { "event=framework_request_error status=${status.value()}" }
        }
        return super.handleExceptionInternal(exception, body as? ErrorResponse ?: ErrorResponse.from(errorCode), headers, status, request)
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
