package com.ject.module.expense.presentation.response

import java.time.Instant

/** 명세의 `LockExpiry`입니다. */
data class ExpenseEditLockExpiryResponse(
    val expiresAt: Instant,
)
