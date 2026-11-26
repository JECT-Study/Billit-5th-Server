package com.ject.module.expense.domain

import com.ject.global.error.ErrorCode

enum class ExpenseErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    AMOUNT_MISMATCH(400, "품목 금액의 합계가 결제 총액과 다릅니다."),
    ;

    override val code: String = "EXPENSE_$name"
}
