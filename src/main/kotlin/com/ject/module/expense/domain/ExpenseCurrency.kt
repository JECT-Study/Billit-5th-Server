package com.ject.module.expense.domain

/**
 * 지출의 결제 통화입니다. 여행 기본 통화와 고를 수 있는 값은 같지만, 지출 금액의 저장 단위를 함께 정하므로 따로 둡니다.
 *
 * [fractionDigits]는 금액을 저장하는 최소 단위의 소수 자릿수입니다. KRW 12000은 12000, USD 12.34는 1234로 저장합니다.
 */
enum class ExpenseCurrency(
    val fractionDigits: Int,
) {
    KRW(0),
    JPY(0),
    USD(2),
    EUR(2),
    CNY(2),
}
