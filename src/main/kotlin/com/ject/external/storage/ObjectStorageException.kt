package com.ject.external.storage

class ObjectStorageException(
    val reason: ObjectStorageFailureReason,
    val operation: String,
    val statusCode: Int?,
    val serviceCode: String?,
    val requestId: String?,
    cause: Throwable? = null,
) : RuntimeException("오브젝트 스토리지 작업에 실패했습니다: $operation (실패 사유=$reason, 상태 코드=$statusCode)", cause)
