package com.ject.module.expense.domain

import com.ject.global.error.ErrorCode

enum class ExpenseErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    AMOUNT_MISMATCH(400, "품목 금액의 합계가 결제 총액과 다릅니다."),
    INVALID_SHARES(400, "결제자나 부담자로 지정할 수 없는 참여자가 있습니다."),
    TRIP_NOT_CONFIRMED(409, "여행을 확정한 뒤에 지출을 등록할 수 있습니다."),
    IDEMPOTENCY_KEY_REUSED(409, "이미 사용된 지출 등록 요청입니다. 다시 시도해주세요."),
    ;

    override val code: String = "EXPENSE_$name"
}
