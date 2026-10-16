package com.ject.global.error

class ErrorResponse(
    val code: String,
    val message: String,
    errors: List<FieldError>? = null,
) {
    val errors: List<FieldError>? = errors?.takeIf { it.isNotEmpty() }?.sortedBy { it.field }

    companion object {
        fun from(
            errorCode: ErrorCode,
            errors: List<FieldError>? = null,
        ): ErrorResponse = ErrorResponse(code = errorCode.code, message = errorCode.message, errors = errors)
    }
}
