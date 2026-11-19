package com.ject.module.auth.domain

import java.time.Instant

interface LoginHistoryRepository {
    fun save(loginHistory: LoginHistory): LoginHistory

    /** [cutoff]보다 먼저 발생한 기록을 지우고 지운 건수를 반환합니다. 자체 트랜잭션에서 커밋합니다. */
    fun deleteOccurredBefore(cutoff: Instant): Int
}
