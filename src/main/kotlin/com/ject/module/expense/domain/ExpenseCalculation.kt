package com.ject.module.expense.domain

/**
 * 저장할 지출 금액의 계산 결과입니다. 결제 통화 금액은 통화의 최소 단위 정수, 원화 금액은 1원 단위 정수입니다.
 *
 * [items]는 요청한 품목 순서와 같고 전체 1/N이면 비어 있습니다. [shares]는 여행 참여자 ID 오름차순이며, 부담액 합계는 [amount], 원화 부담액 합계는 [krwAmount]와 같습니다.
 */
data class ExpenseCalculation(
    val originalAmount: Long,
    val discountAmount: Long,
    val amount: Long,
    val krwAmount: Long,
    val items: List<Item>,
    val shares: List<Share>,
) {
    data class Item(
        val amount: Long,
        val discountAmount: Long,
    )

    data class Share(
        val tripMemberId: Long,
        val amount: Long,
        val krwAmount: Long,
    )
}
