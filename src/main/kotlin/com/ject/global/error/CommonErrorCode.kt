package com.ject.global.error

enum class CommonErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    INVALID_REQUEST(400, "요청이 올바르지 않습니다."),
    UNAUTHORIZED(401, "인증이 필요합니다."),
    FORBIDDEN(403, "요청을 수행할 권한이 없습니다."),
    NOT_FOUND(404, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(405, "지원하지 않는 HTTP 메서드입니다."),
    NOT_ACCEPTABLE(406, "지원하지 않는 응답 형식입니다."),
    CONFLICT(409, "현재 상태에서는 요청을 처리할 수 없습니다."),
    PAYLOAD_TOO_LARGE(413, "업로드 가능한 크기를 초과했습니다."),
    UNSUPPORTED_MEDIA_TYPE(415, "지원하지 않는 미디어 타입입니다."),
    INTERNAL_SERVER_ERROR(500, "서버 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE(503, "일시적으로 요청을 처리할 수 없습니다."),
    ;

    override val code: String = "COMMON_$name"
}
