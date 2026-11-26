package com.ject.module.expense.implement

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.ExpenseCalculation
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.PaymentMethod
import com.ject.module.expense.domain.RateSource
import com.ject.module.expense.domain.SplitType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDate

class ExpenseCalculatorTest :
    FunSpec({
        val calculator = ExpenseCalculator()

        test("설계서 예시 1: 12,000원을 세 명이 전체 1/N으로 나누면 각자 4,000원을 부담한다") {
            // given
            val command = equal(currency = ExpenseCurrency.KRW, originalAmount = "12000.00", tripMemberIds = listOf(1, 2, 3))

            // when
            val result = calculator.calculate(command)

            // then
            assertSoftly {
                result.amount shouldBe 12000
                result.krwAmount shouldBe 12000
                result.items shouldBe emptyList()
                result.shares shouldBe listOf(share(1, 4000, 4000), share(2, 4000, 4000), share(3, 4000, 4000))
            }
        }

        test("설계서 예시 2: 상품 할인은 그 품목에만 반영해 민지 7,000원, 지훈 3,000원을 부담한다") {
            // given
            val command =
                byItem(
                    originalAmount = "12000.00",
                    items =
                        listOf(
                            item(amount = "8000.00", discountAmount = "2000.00", tripMemberIds = listOf(MINJI, JIHUN)),
                            item(amount = "4000.00", discountAmount = "0.00", tripMemberIds = listOf(MINJI)),
                        ),
                )

            // when
            val result = calculator.calculate(command)

            // then
            assertSoftly {
                result.originalAmount shouldBe 12000
                result.discountAmount shouldBe 0
                result.amount shouldBe 10000
                result.items shouldBe listOf(ExpenseCalculation.Item(8000, 2000), ExpenseCalculation.Item(4000, 0))
                result.shares shouldBe listOf(share(MINJI, 7000, 7000), share(JIHUN, 3000, 3000))
            }
        }

        test("설계서 예시 3: 전체 할인 1,000원은 상품 할인을 반영한 부담 비율대로 민지 700원, 지훈 300원으로 나뉜다") {
            // given
            val command =
                byItem(
                    originalAmount = "12000.00",
                    discountAmount = "1000.00",
                    items =
                        listOf(
                            item(amount = "8000.00", discountAmount = "2000.00", tripMemberIds = listOf(MINJI, JIHUN)),
                            item(amount = "4000.00", discountAmount = "0.00", tripMemberIds = listOf(MINJI)),
                        ),
                )

            // when
            val result = calculator.calculate(command)

            // then
            assertSoftly {
                result.amount shouldBe 9000
                result.shares shouldBe listOf(share(MINJI, 6300, 6300), share(JIHUN, 2700, 2700))
            }
        }

        test("설계서 예시 4: 10,000원을 세 명이 나눠 남은 1원은 소수점 이하가 같으므로 여행 참여자 ID가 가장 작은 참여자가 받는다") {
            // given
            val command = equal(currency = ExpenseCurrency.KRW, originalAmount = "10000.00", tripMemberIds = listOf(30, 10, 20))

            // when
            val result = calculator.calculate(command)

            // then
            result.shares shouldBe listOf(share(10, 3334, 3334), share(20, 3333, 3333), share(30, 3333, 3333))
        }

        test("남은 단위는 여행 참여자 ID보다 소수점 이하가 큰 참여자에게 먼저 준다") {
            // given
            // 최종 결제액 999원을 7:3으로 나누면 699.3원과 299.7원이므로 남은 1원은 299.7원인 참여자가 받습니다.
            val command =
                byItem(
                    originalAmount = "1000.00",
                    discountAmount = "1.00",
                    items =
                        listOf(
                            item(amount = "700.00", discountAmount = "0.00", tripMemberIds = listOf(10)),
                            item(amount = "300.00", discountAmount = "0.00", tripMemberIds = listOf(20)),
                        ),
                )

            // when
            val result = calculator.calculate(command)

            // then
            result.shares shouldBe listOf(share(10, 699, 699), share(20, 300, 300))
        }

        test("JPY 지출은 엔 단위로 나누고, 원화 부담액은 원화 환산액을 부담액 비율대로 나눠 합계를 맞춘다") {
            // given
            val command =
                equal(currency = ExpenseCurrency.JPY, originalAmount = "1000.00", exchangeRate = "9.12", tripMemberIds = listOf(1, 2, 3))

            // when
            val result = calculator.calculate(command)

            // then
            // 원화 부담액은 3046.08원, 3036.96원, 3036.96원을 내린 뒤 남은 2원을 소수점 이하가 큰 두 참여자에게 줍니다.
            assertSoftly {
                result.amount shouldBe 1000
                result.krwAmount shouldBe 9120
                result.shares shouldBe listOf(share(1, 334, 3046), share(2, 333, 3037), share(3, 333, 3037))
                result.shares.sumOf { it.krwAmount } shouldBe result.krwAmount
            }
        }

        test("USD 지출은 센트 단위로 저장하고, 원화 환산액은 1원 단위로 반올림한다") {
            // given
            val command =
                equal(currency = ExpenseCurrency.USD, originalAmount = "12.34", exchangeRate = "1380.55", tripMemberIds = listOf(1, 2, 3))

            // when
            val result = calculator.calculate(command)

            // then
            // 12.34달러 × 1380.55 = 17036.007원은 17036원입니다.
            assertSoftly {
                result.originalAmount shouldBe 1234
                result.amount shouldBe 1234
                result.krwAmount shouldBe 17036
                result.shares shouldBe listOf(share(1, 412, 5688), share(2, 411, 5674), share(3, 411, 5674))
                result.shares.sumOf { it.krwAmount } shouldBe result.krwAmount
            }
        }

        test("모든 품목을 전액 할인해 최종 결제액이 0이면 모든 부담자의 부담액은 0이다") {
            // given
            val command =
                byItem(
                    originalAmount = "3000.00",
                    items =
                        listOf(
                            item(amount = "1000.00", discountAmount = "1000.00", tripMemberIds = listOf(1)),
                            item(amount = "2000.00", discountAmount = "2000.00", tripMemberIds = listOf(1, 2)),
                        ),
                )

            // when
            val result = calculator.calculate(command)

            // then
            assertSoftly {
                result.amount shouldBe 0
                result.krwAmount shouldBe 0
                result.shares shouldBe listOf(share(1, 0, 0), share(2, 0, 0))
            }
        }

        test("품목 금액 합계가 할인 전 총액과 다르면 EXPENSE_AMOUNT_MISMATCH로 거절한다") {
            // given
            val command =
                byItem(
                    originalAmount = "12000.00",
                    items = listOf(item(amount = "11000.00", discountAmount = "0.00", tripMemberIds = listOf(1))),
                )

            // when
            val failure = shouldThrow<BusinessException> { calculator.calculate(command) }

            // then
            failure.errorCode shouldBe ExpenseErrorCode.AMOUNT_MISMATCH
        }

        test("통화 최소 단위나 범위를 벗어난 금액과 환율, 0 미만이 되는 최종 결제액은 COMMON_INVALID_REQUEST로 거절한다") {
            // given
            val commands =
                mapOf(
                    "소수가 있는 KRW 금액" to equal(currency = ExpenseCurrency.KRW, originalAmount = "1500.50"),
                    "소수가 있는 JPY 할인액" to equal(currency = ExpenseCurrency.JPY, originalAmount = "1500.00", discountAmount = "0.10"),
                    "0 미만 금액" to equal(currency = ExpenseCurrency.USD, originalAmount = "-1.00"),
                    "정수부 11자리 금액" to equal(currency = ExpenseCurrency.USD, originalAmount = "10000000000.00"),
                    "할인 전 총액보다 큰 전체 할인" to equal(currency = ExpenseCurrency.KRW, originalAmount = "1000.00", discountAmount = "1001.00"),
                    "1이 아닌 KRW 환율" to equal(currency = ExpenseCurrency.KRW, originalAmount = "1000.00", exchangeRate = "1.01"),
                    "0인 환율" to equal(currency = ExpenseCurrency.USD, originalAmount = "10.00", exchangeRate = "0.00"),
                    "정수부 11자리 환율" to equal(currency = ExpenseCurrency.USD, originalAmount = "10.00", exchangeRate = "10000000000.00"),
                    "정수부 11자리 원화 환산액" to
                        equal(currency = ExpenseCurrency.USD, originalAmount = "9999999999.99", exchangeRate = "1380.55"),
                    "품목 금액보다 큰 상품 할인" to
                        byItem(
                            originalAmount = "1000.00",
                            items = listOf(item(amount = "1000.00", discountAmount = "1000.01", tripMemberIds = listOf(1))),
                        ),
                    "상품 할인과 전체 할인의 합이 총액보다 큼" to
                        byItem(
                            originalAmount = "1000.00",
                            discountAmount = "1.00",
                            items = listOf(item(amount = "1000.00", discountAmount = "1000.00", tripMemberIds = listOf(1))),
                        ),
                )

            // when
            val failures = commands.mapValues { shouldThrow<BusinessException> { calculator.calculate(it.value) } }

            // then
            failures.mapValues { it.value.errorCode } shouldBe commands.mapValues { CommonErrorCode.INVALID_REQUEST }
        }

        test("정수부 10자리 금액은 받는다") {
            // given
            val command =
                equal(currency = ExpenseCurrency.USD, originalAmount = "9999999999.99", exchangeRate = "0.10", tripMemberIds = listOf(1))

            // when
            val result = calculator.calculate(command)

            // then
            assertSoftly {
                result.amount shouldBe 999999999999
                result.krwAmount shouldBe 1000000000
            }
        }
    }) {
    private companion object {
        const val MINJI = 1L
        const val JIHUN = 2L

        fun equal(
            currency: ExpenseCurrency,
            originalAmount: String,
            discountAmount: String = "0.00",
            exchangeRate: String = "1.00",
            tripMemberIds: List<Long> = listOf(1),
        ): CreateExpenseCommand =
            command(
                splitType = SplitType.EQUAL,
                currency = currency,
                originalAmount = originalAmount,
                discountAmount = discountAmount,
                exchangeRate = exchangeRate,
                tripMemberIds = tripMemberIds,
                items = null,
            )

        fun byItem(
            originalAmount: String,
            discountAmount: String = "0.00",
            items: List<CreateExpenseCommand.Item>,
        ): CreateExpenseCommand =
            command(
                splitType = SplitType.ITEM,
                currency = ExpenseCurrency.KRW,
                originalAmount = originalAmount,
                discountAmount = discountAmount,
                exchangeRate = "1.00",
                tripMemberIds = null,
                items = items,
            )

        fun command(
            splitType: SplitType,
            currency: ExpenseCurrency,
            originalAmount: String,
            discountAmount: String,
            exchangeRate: String,
            tripMemberIds: List<Long>?,
            items: List<CreateExpenseCommand.Item>?,
        ): CreateExpenseCommand =
            CreateExpenseCommand(
                name = "식사",
                splitType = splitType,
                currency = currency,
                paidDate = LocalDate.parse("2026-11-05"),
                paymentMethod = PaymentMethod.CARD,
                payerTripMemberId = 1,
                originalAmount = BigDecimal(originalAmount),
                discountAmount = BigDecimal(discountAmount),
                exchangeRate = BigDecimal(exchangeRate),
                exchangeRateSource = RateSource.MANUAL,
                tripMemberIds = tripMemberIds,
                items = items,
            )

        fun item(
            amount: String,
            discountAmount: String,
            tripMemberIds: List<Long>,
        ): CreateExpenseCommand.Item =
            CreateExpenseCommand.Item(
                name = null,
                amount = BigDecimal(amount),
                discountAmount = BigDecimal(discountAmount),
                tripMemberIds = tripMemberIds,
            )

        fun share(
            tripMemberId: Long,
            amount: Long,
            krwAmount: Long,
        ): ExpenseCalculation.Share = ExpenseCalculation.Share(tripMemberId = tripMemberId, amount = amount, krwAmount = krwAmount)
    }
}
