package com.ject.module.expense.implement

import com.ject.global.error.BusinessException
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.PaymentMethod
import com.ject.module.expense.domain.RateSource
import com.ject.module.expense.domain.SplitType
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripParticipantResult
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDate

class ExpenseShareValidatorTest :
    FunSpec({
        val validator = ExpenseShareValidator()

        test("수정 전 결제자는 나갔거나 탈퇴해도 결제자로 둘 수 있다") {
            // given
            val keepLeftPayer = equal(payer = LEFT_ID, tripMemberIds = listOf(ADMIN_ID))
            val keepWithdrawnPayer = equal(payer = WITHDRAWN_ID, tripMemberIds = listOf(ADMIN_ID))

            // when
            validator.validate(
                keepLeftPayer,
                PARTICIPANTS,
                retainedPayerTripMemberId = LEFT_ID,
                retainedShareTripMemberIds = setOf(ADMIN_ID),
            )
            validator.validate(
                keepWithdrawnPayer,
                PARTICIPANTS,
                retainedPayerTripMemberId = WITHDRAWN_ID,
                retainedShareTripMemberIds = setOf(ADMIN_ID),
            )
            // 수정 전 결제자가 아니면 같은 입력도 새로 지정한 것이므로 거절합니다.
            val failure =
                shouldThrow<BusinessException> {
                    validator.validate(
                        keepLeftPayer,
                        PARTICIPANTS,
                        retainedPayerTripMemberId = null,
                        retainedShareTripMemberIds = emptySet(),
                    )
                }

            // then
            failure.errorCode shouldBe ExpenseErrorCode.INVALID_SHARES
        }

        test("부담자이기만 했던 나간 회원을 결제자로 바꾸면 거절한다") {
            // given
            // 수정 전 지출은 관리자가 결제하고 관리자와 나간 회원이 나눴습니다.
            val retainedPayer = ADMIN_ID
            val retainedShares = setOf(ADMIN_ID, LEFT_ID)

            // when
            validator.validate(
                equal(payer = ADMIN_ID, tripMemberIds = listOf(ADMIN_ID, LEFT_ID)),
                PARTICIPANTS,
                retainedPayer,
                retainedShares,
            )
            val failure =
                shouldThrow<BusinessException> {
                    validator.validate(
                        equal(payer = LEFT_ID, tripMemberIds = listOf(ADMIN_ID, LEFT_ID)),
                        PARTICIPANTS,
                        retainedPayer,
                        retainedShares,
                    )
                }

            // then
            failure.errorCode shouldBe ExpenseErrorCode.INVALID_SHARES
        }

        test("결제자이기만 했던 나간 회원을 부담자로 넣으면 거절한다") {
            // given
            // 수정 전 지출은 나간 회원이 결제하고 관리자와 로그인 참여자가 나눴습니다.
            val retainedPayer = LEFT_ID
            val retainedShares = setOf(ADMIN_ID, MEMBER_ID)

            // when
            validator.validate(
                equal(payer = LEFT_ID, tripMemberIds = listOf(ADMIN_ID, MEMBER_ID)),
                PARTICIPANTS,
                retainedPayer,
                retainedShares,
            )
            val failure =
                shouldThrow<BusinessException> {
                    validator.validate(
                        equal(payer = LEFT_ID, tripMemberIds = listOf(ADMIN_ID, MEMBER_ID, LEFT_ID)),
                        PARTICIPANTS,
                        retainedPayer,
                        retainedShares,
                    )
                }

            // then
            failure.errorCode shouldBe ExpenseErrorCode.INVALID_SHARES
        }

        test("수정 전 부담자는 품목이 바뀌어도 어느 품목에든 둘 수 있다") {
            // given
            // 수정 전 부담자 전체에 나간 회원과 탈퇴 회원이 있었고, 수정하면서 품목을 새로 나눕니다.
            val command =
                byItem(payer = ADMIN_ID, items = listOf(listOf(ADMIN_ID), listOf(LEFT_ID, WITHDRAWN_ID), listOf(ADMIN_ID, LEFT_ID)))

            // when
            validator.validate(
                command,
                PARTICIPANTS,
                retainedPayerTripMemberId = ADMIN_ID,
                retainedShareTripMemberIds = setOf(ADMIN_ID, LEFT_ID, WITHDRAWN_ID),
            )
            // 수정 전 부담자가 아니었으면 같은 품목 구성도 거절합니다.
            val failure =
                shouldThrow<BusinessException> {
                    validator.validate(
                        command,
                        PARTICIPANTS,
                        retainedPayerTripMemberId = ADMIN_ID,
                        retainedShareTripMemberIds = setOf(ADMIN_ID),
                    )
                }

            // then
            failure.errorCode shouldBe ExpenseErrorCode.INVALID_SHARES
        }

        test("비로그인 참여자는 수정 전 지출과 관계없이 결제자로 지정할 수 없다") {
            // given
            val guestPayer = equal(payer = GUEST_ID, tripMemberIds = listOf(ADMIN_ID, GUEST_ID))

            // when
            val onCreate =
                shouldThrow<BusinessException> {
                    validator.validate(guestPayer, PARTICIPANTS, retainedPayerTripMemberId = null, retainedShareTripMemberIds = emptySet())
                }
            // 비로그인 참여자가 부담자였던 지출을 수정하면서 결제자로 바꾸는 경우입니다.
            val onUpdate =
                shouldThrow<BusinessException> {
                    validator.validate(
                        guestPayer,
                        PARTICIPANTS,
                        retainedPayerTripMemberId = ADMIN_ID,
                        retainedShareTripMemberIds = setOf(ADMIN_ID, GUEST_ID),
                    )
                }

            // then
            assertSoftly {
                onCreate.errorCode shouldBe ExpenseErrorCode.INVALID_SHARES
                onUpdate.errorCode shouldBe ExpenseErrorCode.INVALID_SHARES
            }
        }
    }) {
    private companion object {
        const val ADMIN_ID = 1L
        const val MEMBER_ID = 2L
        const val GUEST_ID = 3L
        const val LEFT_ID = 4L
        const val WITHDRAWN_ID = 5L

        /** 확정된 여행의 관리자, 로그인 참여자, 비로그인 참여자, 나간 회원과 탈퇴 회원입니다. */
        val PARTICIPANTS =
            listOf(
                participant(ADMIN_ID, TripMemberRole.ADMIN, TripMemberStatus.MEMBER),
                participant(MEMBER_ID, TripMemberRole.MEMBER, TripMemberStatus.MEMBER),
                participant(GUEST_ID, TripMemberRole.MEMBER, TripMemberStatus.GUEST),
                participant(LEFT_ID, TripMemberRole.MEMBER, TripMemberStatus.LEFT),
                participant(WITHDRAWN_ID, TripMemberRole.MEMBER, TripMemberStatus.WITHDRAWN),
            )

        fun participant(
            tripMemberId: Long,
            role: TripMemberRole,
            status: TripMemberStatus,
        ): TripParticipantResult =
            TripParticipantResult(
                tripMemberId = tripMemberId,
                memberId = if (status == TripMemberStatus.GUEST) null else 100 + tripMemberId,
                role = role,
                status = status,
            )

        fun equal(
            payer: Long,
            tripMemberIds: List<Long>,
        ): CreateExpenseCommand = command(SplitType.EQUAL, payer, tripMemberIds = tripMemberIds, items = null)

        /** [items]는 품목마다 부담자의 여행 참여자 ID입니다. 금액은 검증에 쓰지 않으므로 품목마다 1,000원으로 둡니다. */
        fun byItem(
            payer: Long,
            items: List<List<Long>>,
        ): CreateExpenseCommand =
            command(
                SplitType.ITEM,
                payer,
                tripMemberIds = null,
                items = items.map { CreateExpenseCommand.Item(null, BigDecimal("1000.00"), BigDecimal("0.00"), it) },
            )

        fun command(
            splitType: SplitType,
            payer: Long,
            tripMemberIds: List<Long>?,
            items: List<CreateExpenseCommand.Item>?,
        ): CreateExpenseCommand =
            CreateExpenseCommand(
                name = "식사",
                splitType = splitType,
                currency = ExpenseCurrency.KRW,
                paidDate = LocalDate.parse("2026-11-05"),
                paymentMethod = PaymentMethod.CARD,
                payerTripMemberId = payer,
                originalAmount = BigDecimal("3000.00"),
                discountAmount = BigDecimal("0.00"),
                exchangeRate = BigDecimal("1.00"),
                exchangeRateSource = RateSource.MANUAL,
                tripMemberIds = tripMemberIds,
                items = items,
            )
    }
}
