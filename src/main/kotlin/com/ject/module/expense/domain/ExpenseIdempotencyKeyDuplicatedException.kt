package com.ject.module.expense.domain

/**
 * 같은 지출 생성 고유키의 지출이 이미 저장돼 있어 지출 저장이 유니크 제약에 막혔음을 알립니다.
 *
 * 저장 트랜잭션은 이 예외로 롤백됩니다. PostgreSQL은 제약 위반이 난 트랜잭션에서 더 조회할 수 없으므로, 기존 지출 조회는 트랜잭션 밖에서 합니다.
 */
class ExpenseIdempotencyKeyDuplicatedException(
    cause: Throwable,
) : RuntimeException("같은 고유키의 지출이 이미 저장돼 있습니다.", cause)
