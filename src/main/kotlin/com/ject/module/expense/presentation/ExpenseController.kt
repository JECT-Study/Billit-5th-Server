package com.ject.module.expense.presentation

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.global.response.ApiResponse
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.SplitType
import com.ject.module.expense.presentation.request.CreateExpenseRequest
import com.ject.module.expense.presentation.response.ExpenseDetailResponse
import com.ject.module.expense.usecase.CreateExpenseUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@RestController
@RequestMapping("/api/v1/trips/{tripId}/expenses")
class ExpenseController(
    private val createExpenseUseCase: CreateExpenseUseCase,
) {
    /** 처음 만든 요청과 같은 회원이 같은 고유키로 다시 보낸 요청 모두 201로 응답합니다. 지출 상세 조회 API가 아직 없어 `Location`은 넣지 않습니다. */
    @PostMapping
    fun createExpense(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable tripId: Long,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) @Pattern(regexp = UUID_PATTERN) idempotencyKey: String,
        @Valid @RequestBody request: CreateExpenseRequest,
    ): ResponseEntity<ApiResponse<ExpenseDetailResponse>> {
        val command =
            CreateExpenseCommand(
                name = request.name,
                splitType =
                    when (request.splitType) {
                        ExpenseSplitType.EQUAL -> SplitType.EQUAL
                        ExpenseSplitType.BY_ITEM -> SplitType.ITEM
                    },
                currency = request.currency,
                paidDate = request.paidDate,
                paymentMethod = request.paymentMethod,
                payerTripMemberId = id(request.payerTripMemberId),
                originalAmount = BigDecimal(request.originalAmount),
                discountAmount = BigDecimal(request.discountAmount),
                exchangeRate = BigDecimal(request.exchangeRate),
                exchangeRateSource = request.exchangeRateSource,
                tripMemberIds = request.tripMemberIds?.map { id(it) },
                items =
                    request.items?.map { item ->
                        CreateExpenseCommand.Item(
                            name = item.name,
                            amount = BigDecimal(item.amount),
                            discountAmount = BigDecimal(item.discountAmount),
                            tripMemberIds = item.tripMemberIds.map { id(it) },
                        )
                    },
            )

        val expense = createExpenseUseCase.create(memberId(accessToken), tripId, UUID.fromString(idempotencyKey), command)

        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse(
                ExpenseDetailResponse(
                    expenseId = expense.expenseId.toString(),
                    name = expense.name,
                    paidDate = expense.paidDate,
                    currency = expense.currency,
                    amount = expense.amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    payerTripMemberId = expense.payerTripMemberId.toString(),
                    settlementId = expense.settlementId?.toString(),
                    isEditLocked = expense.isEditLocked,
                    tripId = expense.tripId.toString(),
                    splitType =
                        when (expense.splitType) {
                            SplitType.EQUAL -> ExpenseSplitType.EQUAL
                            SplitType.ITEM -> ExpenseSplitType.BY_ITEM
                        },
                    createdByTripMemberId = expense.createdByTripMemberId.toString(),
                    paymentMethod = expense.paymentMethod,
                    originalAmount = expense.originalAmount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    discountAmount = expense.discountAmount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    exchangeRate = expense.exchangeRate.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    exchangeRateSource = expense.exchangeRateSource,
                    exchangeRateProvider = expense.exchangeRateProvider,
                    exchangeRateBaseDate = expense.exchangeRateBaseDate,
                    amountKrw = expense.amountKrw.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    items =
                        expense.items.map {
                            ExpenseDetailResponse.Item(
                                expenseItemId = it.expenseItemId.toString(),
                                name = it.name,
                                amount = it.amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                discountAmount = it.discountAmount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                sortOrder = it.sortOrder,
                                tripMemberIds = it.tripMemberIds.map { tripMemberId -> tripMemberId.toString() },
                            )
                        },
                    shares =
                        expense.shares.map {
                            ExpenseDetailResponse.Share(
                                tripMemberId = it.tripMemberId.toString(),
                                amount = it.amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                amountKrw = it.amountKrw.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                            )
                        },
                ),
            ),
        )
    }

    /** 숫자가 아닌 ID는 어떤 참여자도 가리킬 수 없으므로 경로의 숫자 ID와 같이 400으로 거절합니다. */
    private fun id(value: String): Long = value.toLongOrNull() ?: throw BusinessException(CommonErrorCode.INVALID_REQUEST)

    private fun memberId(accessToken: Jwt): Long = checkNotNull(accessToken.subject).toLong()

    companion object {
        private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

        /** 8-4-4-4-12자리 16진수 표기만 받습니다. `UUID.fromString`은 자릿수가 모자란 값도 받으므로 형식을 먼저 확인합니다. */
        private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    }
}
