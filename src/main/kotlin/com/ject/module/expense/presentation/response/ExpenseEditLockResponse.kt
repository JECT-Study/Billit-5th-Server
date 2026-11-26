package com.ject.module.expense.presentation.response

import java.time.Instant

/** 명세의 `EditLock`입니다. */
data class ExpenseEditLockResponse(
    val lockToken: String,
    val expiresAt: Instant,
)
