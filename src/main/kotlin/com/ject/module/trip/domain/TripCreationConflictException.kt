package com.ject.module.trip.domain

/**
 * 여행 저장이 고유키나 초대 코드의 유니크 제약에 막혔음을 알립니다.
 *
 * 저장 트랜잭션은 이 예외로 롤백됩니다. PostgreSQL은 제약 위반이 난 트랜잭션에서 더 조회할 수 없으므로, 기존 여행 조회나 재시도는 트랜잭션 밖에서 합니다.
 */
class TripCreationConflictException(
    val conflict: TripCreationConflict,
    cause: Throwable,
) : RuntimeException("여행 저장이 유니크 제약에 막혔습니다. conflict=$conflict", cause)
