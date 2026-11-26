package com.ject.module.expense.domain

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 영수증 한 장에서 읽은 지출 입력값입니다. 인식 엔진과 관계없이 같은 모델이며, 읽지 못한 값은 `null`, 읽은 품목이 없으면 [items]는 비어 있습니다.
 *
 * 금액은 결제 통화 단위 값이고 범위와 자릿수는 확인하기 전입니다. 카드번호, 카드 승인번호, 영수증 번호, 카드사, 가맹점 사업자번호, 주소와 전화번호는
 * 담을 필드가 없으므로 엔진이 읽어도 앱에 전달되지 않습니다.
 */
data class ReceiptRecognitionResult(
    val name: String?,
    val paidDate: LocalDate?,
    val currency: ExpenseCurrency?,
    val originalAmount: BigDecimal?,
    val discountAmount: BigDecimal?,
    val items: List<Item>,
) {
    /** [amount]는 상품 할인 전 품목 금액, [discountAmount]는 그 품목의 상품 할인액입니다. */
    data class Item(
        val name: String?,
        val amount: BigDecimal?,
        val discountAmount: BigDecimal?,
    )
}
