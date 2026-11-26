package com.ject.module.expense.presentation.request

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.PaymentMethod
import com.ject.module.expense.domain.RateSource
import com.ject.module.expense.presentation.ExpenseSplitType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * 지출 생성 요청입니다. 명세의 `ExpenseInput`입니다.
 *
 * 금액과 환율은 소수점 이하 2자리 문자열인지만 검사하고, 범위와 통화의 최소 단위는 UseCase가 확인합니다. ID는 문자열로 받아 Controller가 숫자로 바꿉니다.
 * [discountAmount]를 생략하면 할인이 없습니다. [tripMemberIds]와 [items]는 분배 방식에 따라 한쪽만 보내며, 분배 방식과의 조합은 UseCase가 확인합니다.
 * 생략할 수 있는 필드도 `null`과 목록 안의 `null`은 받지 않습니다.
 */
data class CreateExpenseRequest(
    @field:NotBlank
    @field:Size(max = NAME_MAX_LENGTH)
    val name: String,
    val splitType: ExpenseSplitType,
    val currency: ExpenseCurrency,
    val paidDate: LocalDate,
    val paymentMethod: PaymentMethod,
    val payerTripMemberId: String,
    @field:Pattern(regexp = DECIMAL_PATTERN)
    val originalAmount: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:Pattern(regexp = DECIMAL_PATTERN)
    val discountAmount: String = "0.00",
    @field:Pattern(regexp = DECIMAL_PATTERN)
    val exchangeRate: String,
    val exchangeRateSource: RateSource,
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val tripMemberIds: List<String>? = null,
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    @field:Valid
    val items: List<Item>? = null,
) {
    /** 명세의 `ExpenseItemInput`입니다. [name]을 생략하거나 `null`로 보내면 이름 없는 품목입니다. */
    data class Item(
        @field:Pattern(regexp = NOT_BLANK_PATTERN)
        @field:Size(max = NAME_MAX_LENGTH)
        val name: String? = null,
        @field:Pattern(regexp = DECIMAL_PATTERN)
        val amount: String,
        @field:Pattern(regexp = DECIMAL_PATTERN)
        val discountAmount: String,
        @param:JsonSetter(contentNulls = Nulls.FAIL)
        val tripMemberIds: List<String>,
    )

    companion object {
        /** 명세 `DecimalString`과 같은 소수점 이하 2자리 문자열입니다. */
        private const val DECIMAL_PATTERN = "-?(0|[1-9][0-9]*)\\.[0-9]{2}"

        /** 공백이 아닌 문자를 하나 이상 포함합니다. 값이 없으면 검사하지 않습니다. */
        private const val NOT_BLANK_PATTERN = "(?s).*\\S.*"

        /** `expense.name`과 `expense_item.name`의 최대 길이입니다. */
        private const val NAME_MAX_LENGTH = 50
    }
}
