package com.ject.module.expense.implement

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.ExpenseCalculation
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.SplitType
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * 지출의 최종 결제액, 참여자별 부담액과 원화 환산액을 계산합니다. DB를 읽거나 쓰지 않습니다.
 *
 * - 금액은 통화의 최소 단위 정수로 바꿔 계산합니다. KRW와 JPY는 1, USD, EUR과 CNY는 0.01이 최소 단위입니다.
 * - 부담 비율은 품목마다 상품 할인 뒤 금액을 그 품목의 부담자에게 똑같이 나눈 합입니다. 전체 1/N은 할인 전 총액을 선택한 참여자에게 똑같이 나눕니다.
 * - 참여자별 부담액은 최종 결제액을 부담 비율대로 나눈 값입니다. 전체 할인도 이 비율로 나뉩니다.
 *   반올림은 마지막에 한 번만 합니다. 최소 단위로 내린 뒤 남은 단위를 소수점 이하가 큰 참여자부터, 같으면 여행 참여자 ID가 작은 참여자부터 하나씩 줍니다.
 * - 원화 환산액은 최종 결제액에 환율을 곱해 1원 단위로 반올림(HALF_UP)합니다. 참여자별 원화 부담액은 원화 환산액을 부담액 비율대로 같은 방식으로 나눠 합계를 맞춥니다.
 */
@Component
class ExpenseCalculator {
    /**
     * 1. 금액이 0 미만이거나 정수부가 10자리를 넘거나 통화의 최소 단위보다 작은 소수가 있으면 400입니다.
     *    환율이 0 이하이거나 정수부가 10자리를 넘으면 400이고, KRW 지출은 환율이 1이 아니면 400입니다.
     * 2. 품목별 지출에서 품목 금액(상품 할인 전)의 합계가 할인 전 총액과 다르면 400 `EXPENSE_AMOUNT_MISMATCH`입니다.
     * 3. 상품 할인이 품목 금액보다 크거나, 최종 결제액이 0 미만이거나, 원화 환산액의 정수부가 10자리를 넘으면 400입니다.
     *
     * 부담자 목록은 1명 이상이고 겹치지 않는다고 가정합니다. 부담자가 같은 여행의 참여자인지는 호출하는 쪽이 확인합니다.
     */
    fun calculate(command: CreateExpenseCommand): ExpenseCalculation {
        val currency = command.currency
        val exchangeRate = command.exchangeRate
        if (exchangeRate.signum() <= 0 || exchangeRate >= AMOUNT_LIMIT ||
            (currency == ExpenseCurrency.KRW && exchangeRate.compareTo(BigDecimal.ONE) != 0)
        ) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }
        val originalAmount = toMinorUnits(command.originalAmount, currency)
        val discountAmount = toMinorUnits(command.discountAmount, currency)
        val items =
            command.items.orEmpty().map {
                ExpenseCalculation.Item(
                    amount = toMinorUnits(it.amount, currency),
                    discountAmount = toMinorUnits(it.discountAmount, currency),
                )
            }

        if (command.splitType == SplitType.ITEM && items.sumOf { it.amount } != originalAmount) {
            throw BusinessException(ExpenseErrorCode.AMOUNT_MISMATCH)
        }
        val amount = originalAmount - items.sumOf { it.discountAmount } - discountAmount
        if (items.any { it.discountAmount > it.amount } || amount < 0) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }
        val krwAmount = BigDecimal.valueOf(amount, currency.fractionDigits).multiply(exchangeRate).setScale(0, RoundingMode.HALF_UP)
        if (krwAmount >= AMOUNT_LIMIT) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }

        val sharedAmounts =
            when (command.splitType) {
                SplitType.EQUAL -> {
                    listOf(SharedAmount(originalAmount, checkNotNull(command.tripMemberIds)))
                }

                SplitType.ITEM -> {
                    items.zip(
                        checkNotNull(command.items),
                    ) { item, input -> SharedAmount(item.amount - item.discountAmount, input.tripMemberIds) }
                }
            }
        val shareAmounts = allocate(amount, weights(sharedAmounts))
        val shareKrwAmounts = allocate(krwAmount.longValueExact(), shareAmounts.mapValues { BigInteger.valueOf(it.value) })

        return ExpenseCalculation(
            originalAmount = originalAmount,
            discountAmount = discountAmount,
            amount = amount,
            krwAmount = krwAmount.longValueExact(),
            items = items,
            shares =
                shareAmounts.keys.sorted().map {
                    ExpenseCalculation.Share(
                        tripMemberId = it,
                        amount = shareAmounts.getValue(it),
                        krwAmount = shareKrwAmounts.getValue(it),
                    )
                },
        )
    }

    private fun toMinorUnits(
        amount: BigDecimal,
        currency: ExpenseCurrency,
    ): Long {
        if (amount.signum() < 0 || amount >= AMOUNT_LIMIT || amount.stripTrailingZeros().scale() > currency.fractionDigits) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }
        return amount.movePointRight(currency.fractionDigits).longValueExact()
    }

    /** 참여자별 부담 비율입니다. 부담자 수의 최소공배수를 곱해 균등 분할을 반올림 없는 정수로 나타냅니다. */
    private fun weights(sharedAmounts: List<SharedAmount>): Map<Long, BigInteger> {
        val multiplier =
            sharedAmounts.fold(BigInteger.ONE) { lcm, shared ->
                val size = BigInteger.valueOf(shared.tripMemberIds.size.toLong())
                lcm.multiply(size).divide(lcm.gcd(size))
            }
        val weights = mutableMapOf<Long, BigInteger>()
        for (shared in sharedAmounts) {
            val perMember =
                BigInteger
                    .valueOf(
                        shared.amount,
                    ).multiply(multiplier)
                    .divide(BigInteger.valueOf(shared.tripMemberIds.size.toLong()))
            for (tripMemberId in shared.tripMemberIds) {
                weights.merge(tripMemberId, perMember, BigInteger::add)
            }
        }
        return weights
    }

    /** [total]을 [weights] 비율대로 나눕니다. 비율의 합이 0이면 모두 0입니다. */
    private fun allocate(
        total: Long,
        weights: Map<Long, BigInteger>,
    ): Map<Long, Long> {
        val weightSum = weights.values.fold(BigInteger.ZERO, BigInteger::add)
        if (weightSum.signum() == 0) {
            return weights.mapValues { 0L }
        }
        val divided = weights.mapValues { BigInteger.valueOf(total).multiply(it.value).divideAndRemainder(weightSum) }
        val floored = divided.mapValues { it.value[0].longValueExact() }
        val leftover = total - floored.values.sum()
        val receivers =
            divided.entries
                .sortedWith(compareByDescending<Map.Entry<Long, Array<BigInteger>>> { it.value[1] }.thenBy { it.key })
                .take(Math.toIntExact(leftover))
                .map { it.key }
                .toSet()
        return floored.mapValues { if (it.key in receivers) it.value + 1 else it.value }
    }

    /** 부담자끼리 똑같이 나눌 금액입니다. 품목별 지출이면 상품 할인 뒤 품목 금액, 전체 1/N이면 할인 전 총액입니다. */
    private class SharedAmount(
        val amount: Long,
        val tripMemberIds: List<Long>,
    )

    companion object {
        /** 금액, 환율과 원화 환산액의 상한(미포함)입니다. 정수부는 10자리까지 받습니다. */
        private val AMOUNT_LIMIT = BigDecimal("10000000000")
    }
}
