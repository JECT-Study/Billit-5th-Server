package com.ject.module.expense.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 새로 건 지출 수정 잠금입니다. [lockToken]은 같은 수정 작업의 연장, 해제와 저장 요청에 씁니다. */
data class ExpenseEditLockResult(
    val lockToken: UUID,
    val expiresAt: Instant,
) {
    companion object {
        /** 획득한 잠금은 요청 시각에서 이만큼 뒤(초 단위로 내림)에 만료됩니다. */
        val DURATION: Duration = Duration.ofMinutes(5)

        /** 연장할 때마다 기존 만료 시각에 이만큼 더합니다. */
        val EXTENSION: Duration = Duration.ofMinutes(2)
    }
}
