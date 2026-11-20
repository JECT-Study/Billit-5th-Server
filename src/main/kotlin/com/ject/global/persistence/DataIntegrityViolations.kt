package com.ject.global.persistence

import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException

/**
 * 유니크 제약 위반이면 위반한 제약 이름을, 다른 무결성 오류이거나 이름을 알 수 없으면 `null`을 반환합니다.
 *
 * 유니크 위반 여부는 Hibernate가 SQLSTATE로 정한 [ConstraintViolationException.ConstraintKind.UNIQUE]로 판단합니다.
 * 제약 이름은 Flyway migration에서 붙인 이름 그대로이며, 어느 제약을 어떤 업무 실패로 바꿀지는 각 업무의 Repository Adapter가 정합니다.
 * PostgreSQL dialect는 영어 오류 메시지에서 제약 이름을 읽으므로, DB의 `lc_messages`가 영어가 아니면 이름을 알 수 없어 `null`입니다.
 */
fun DataIntegrityViolationException.violatedUniqueConstraint(): String? {
    val violation =
        generateSequence(cause) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .firstOrNull() ?: return null
    if (violation.kind != ConstraintViolationException.ConstraintKind.UNIQUE) {
        return null
    }
    return violation.constraintName
}
