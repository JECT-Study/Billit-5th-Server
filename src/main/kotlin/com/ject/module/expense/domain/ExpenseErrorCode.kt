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
    EDIT_LOCKED(409, "다른 참여자가 수정 중인 지출입니다."),
    EDIT_LOCK_INVALID(409, "수정 가능 시간이 지났거나 다른 화면에서 수정을 시작했습니다. 다시 수정해주세요."),
    SETTLEMENT_LOCKED(409, "정산 중이거나 정산한 지출은 수정하거나 삭제할 수 없습니다."),
    RECEIPT_TOO_LARGE(413, "15MB 이하의 영수증 이미지를 선택해주세요."),
    RECEIPT_UNSUPPORTED(415, "JPG, PNG, HEIC, WEBP 형식의 영수증 이미지를 선택해주세요."),
    NOT_RECEIPT(400, "영수증을 찾지 못했습니다. 영수증 사진을 다시 선택해주세요."),
    RECOGNITION_UNAVAILABLE(503, "영수증 인식을 잠시 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    ;

    override val code: String = "EXPENSE_$name"
}
