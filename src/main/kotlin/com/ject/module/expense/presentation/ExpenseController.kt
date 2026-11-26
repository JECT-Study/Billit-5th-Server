package com.ject.module.expense.presentation

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.global.pagination.PageResponse
import com.ject.global.response.ApiResponse
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.ExpenseDetailResult
import com.ject.module.expense.domain.SplitType
import com.ject.module.expense.presentation.request.CreateExpenseRequest
import com.ject.module.expense.presentation.request.ListExpensesRequest
import com.ject.module.expense.presentation.response.ExpenseDetailResponse
import com.ject.module.expense.presentation.response.ExpenseSummaryResponse
import com.ject.module.expense.usecase.CreateExpenseUseCase
import com.ject.module.expense.usecase.GetExpenseDetailUseCase
import com.ject.module.expense.usecase.ListExpensesUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/trips/{tripId}/expenses")
class ExpenseController(
    private val createExpenseUseCase: CreateExpenseUseCase,
    private val listExpensesUseCase: ListExpensesUseCase,
    private val getExpenseDetailUseCase: GetExpenseDetailUseCase,
) {
    /** 처음 만든 요청과 같은 회원이 같은 고유키로 다시 보낸 요청 모두 201과 `Location`으로 응답합니다. */
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

        return ResponseEntity
            .created(URI.create("/api/v1/trips/$tripId/expenses/${expense.expenseId}"))
            .body(ApiResponse(expense.toResponse()))
    }

    @GetMapping
    fun listExpenses(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable tripId: Long,
        @Valid request: ListExpensesRequest,
    ): ResponseEntity<PageResponse<ExpenseSummaryResponse>> {
        val result = listExpensesUseCase.list(memberId(accessToken), tripId, request.fromDate, request.toDate, request.page, request.size)

        return ResponseEntity.ok(
            PageResponse.from(
                data =
                    result.items.map {
                        ExpenseSummaryResponse(
                            expenseId = it.expenseId.toString(),
                            name = it.name,
                            paidDate = it.paidDate,
                            currency = it.currency,
                            amount = it.amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                            payerTripMemberId = it.payerTripMemberId.toString(),
                            settlementId = it.settlementId?.toString(),
                            isEditLocked = it.isEditLocked,
                        )
                    },
                page = request.page,
                size = request.size,
                totalElements = result.totalElements,
                hasNext = result.hasNext,
            ),
        )
    }

    @GetMapping("/{expenseId}")
    fun getExpense(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable tripId: Long,
        @PathVariable expenseId: Long,
    ): ResponseEntity<ApiResponse<ExpenseDetailResponse>> =
        ResponseEntity.ok(ApiResponse(getExpenseDetailUseCase.get(memberId(accessToken), tripId, expenseId).toResponse()))

    /** 숫자가 아닌 ID는 어떤 참여자도 가리킬 수 없으므로 경로의 숫자 ID와 같이 400으로 거절합니다. */
    private fun id(value: String): Long = value.toLongOrNull() ?: throw BusinessException(CommonErrorCode.INVALID_REQUEST)

    private fun ExpenseDetailResult.toResponse(): ExpenseDetailResponse =
        ExpenseDetailResponse(
            expenseId = expenseId.toString(),
            name = name,
            paidDate = paidDate,
            currency = currency,
            amount = amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            payerTripMemberId = payerTripMemberId.toString(),
            settlementId = settlementId?.toString(),
            isEditLocked = isEditLocked,
            tripId = tripId.toString(),
            splitType =
                when (splitType) {
                    SplitType.EQUAL -> ExpenseSplitType.EQUAL
                    SplitType.ITEM -> ExpenseSplitType.BY_ITEM
                },
            createdByTripMemberId = createdByTripMemberId.toString(),
            paymentMethod = paymentMethod,
            originalAmount = originalAmount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            discountAmount = discountAmount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            exchangeRate = exchangeRate.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            exchangeRateSource = exchangeRateSource,
            exchangeRateProvider = exchangeRateProvider,
            exchangeRateBaseDate = exchangeRateBaseDate,
            amountKrw = amountKrw.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            items =
                items.map {
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
                shares.map {
                    ExpenseDetailResponse.Share(
                        tripMemberId = it.tripMemberId.toString(),
                        amount = it.amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        amountKrw = it.amountKrw.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    )
                },
        )

    private fun memberId(accessToken: Jwt): Long = checkNotNull(accessToken.subject).toLong()

    companion object {
        private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

        /** 8-4-4-4-12자리 16진수 표기만 받습니다. `UUID.fromString`은 자릿수가 모자란 값도 받으므로 형식을 먼저 확인합니다. */
        private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    }
}
