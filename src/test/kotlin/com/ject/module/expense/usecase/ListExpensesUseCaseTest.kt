package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.ExpenseListResult
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ListExpensesUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var listExpensesUseCase: ListExpensesUseCase

    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM expense WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("결제 날짜의 역순, 같으면 지출 ID의 역순으로 정렬하고 삭제된 지출과 다른 여행의 지출은 빼고 페이지로 나눈다") {
            // given
            val trip = createTrip()
            val otherTrip = createTrip()
            val first = insertExpense(trip, "2026-11-05")
            val second = insertExpense(trip, "2026-11-06")
            val third = insertExpense(trip, "2026-11-05")
            insertExpense(trip, "2026-11-07", deletedAt = Instant.now())
            insertExpense(otherTrip, "2026-11-08")

            // when
            val firstPage = listExpensesUseCase.list(trip.adminMemberId, trip.tripId, null, null, page = 0, size = 2)
            val secondPage = listExpensesUseCase.list(trip.adminMemberId, trip.tripId, null, null, page = 1, size = 2)
            val beyondLastPage = listExpensesUseCase.list(trip.adminMemberId, trip.tripId, null, null, page = 2, size = 2)

            // then
            assertSoftly {
                firstPage.items.map { it.expenseId } shouldBe listOf(second, third)
                firstPage.totalElements shouldBe 3
                firstPage.hasNext shouldBe true
                secondPage.items.map { it.expenseId } shouldBe listOf(first)
                secondPage.hasNext shouldBe false
                beyondLastPage.items shouldBe emptyList()
                beyondLastPage.totalElements shouldBe 3
            }
        }

        test("fromDate와 toDate는 그 날짜의 지출도 포함하고 생략한 쪽은 경계를 두지 않는다") {
            // given
            val trip = createTrip()
            val beforeFrom = insertExpense(trip, "2026-11-04")
            val onFrom = insertExpense(trip, "2026-11-05")
            val onTo = insertExpense(trip, "2026-11-06")
            val afterTo = insertExpense(trip, "2026-11-07")
            val from = LocalDate.parse("2026-11-05")
            val to = LocalDate.parse("2026-11-06")

            // when
            val both = listExpensesUseCase.list(trip.adminMemberId, trip.tripId, from, to, 0, 20)
            val fromOnly = listExpensesUseCase.list(trip.adminMemberId, trip.tripId, from, null, 0, 20)
            val toOnly = listExpensesUseCase.list(trip.adminMemberId, trip.tripId, null, to, 0, 20)
            val sameDay = listExpensesUseCase.list(trip.adminMemberId, trip.tripId, from, from, 0, 20)

            // then
            assertSoftly {
                both.items.map { it.expenseId } shouldBe listOf(onTo, onFrom)
                both.totalElements shouldBe 2
                fromOnly.items.map { it.expenseId } shouldBe listOf(afterTo, onTo, onFrom)
                toOnly.items.map { it.expenseId } shouldBe listOf(onTo, onFrom, beforeFrom)
                sameDay.items.map { it.expenseId } shouldBe listOf(onFrom)
            }
        }

        test("fromDate가 toDate보다 뒤면 400 COMMON_INVALID_REQUEST로 거절한다") {
            // given
            val trip = createTrip()

            // when
            val failure =
                shouldThrow<BusinessException> {
                    listExpensesUseCase.list(
                        trip.adminMemberId,
                        trip.tripId,
                        LocalDate.parse("2026-11-06"),
                        LocalDate.parse("2026-11-05"),
                        0,
                        20,
                    )
                }

            // then
            failure.errorCode shouldBe CommonErrorCode.INVALID_REQUEST
        }

        test("최종 결제액을 통화 단위로 바꾸고 만료되지 않은 수정 잠금만 잠긴 것으로 본다") {
            // given
            val trip = createTrip()
            val now = Instant.now()
            val locked =
                insertExpense(trip, "2026-11-07", currency = "USD", amount = 1234, editLockExpiresAt = now.plus(Duration.ofHours(1)))
            val expired = insertExpense(trip, "2026-11-06", editLockExpiresAt = now.minus(Duration.ofMinutes(1)))
            val unlocked = insertExpense(trip, "2026-11-05")

            // when
            val result = listExpensesUseCase.list(trip.memberMemberId, trip.tripId, null, null, 0, 20)

            // then
            result.items shouldBe
                listOf(
                    item(locked, "2026-11-07", ExpenseCurrency.USD, BigDecimal("12.34"), trip.adminId, isEditLocked = true),
                    item(expired, "2026-11-06", ExpenseCurrency.KRW, BigDecimal("12000"), trip.adminId, isEditLocked = false),
                    item(unlocked, "2026-11-05", ExpenseCurrency.KRW, BigDecimal("12000"), trip.adminId, isEditLocked = false),
                )
        }

        test("여행이 없거나 삭제됐으면 404, 참여하지 않았거나 나간 회원이면 403으로 거절한다") {
            // given
            val trip = createTrip()
            val deletedTrip = createTrip()
            jdbc.update("UPDATE trip SET deleted_at = now() WHERE id = ?", deletedTrip.tripId)
            val outsiderMemberId = createMember()

            // when
            val failures =
                mapOf(
                    "없는 여행" to Pair(trip.adminMemberId, Long.MAX_VALUE),
                    "삭제된 여행" to Pair(deletedTrip.adminMemberId, deletedTrip.tripId),
                    "참여하지 않은 회원" to Pair(outsiderMemberId, trip.tripId),
                    "나간 회원" to Pair(trip.leftMemberId, trip.tripId),
                ).mapValues { (_, request) ->
                    shouldThrow<BusinessException> { listExpensesUseCase.list(request.first, request.second, null, null, 0, 20) }.errorCode
                }

            // then
            failures shouldBe
                mapOf(
                    "없는 여행" to CommonErrorCode.NOT_FOUND,
                    "삭제된 여행" to CommonErrorCode.NOT_FOUND,
                    "참여하지 않은 회원" to CommonErrorCode.FORBIDDEN,
                    "나간 회원" to CommonErrorCode.FORBIDDEN,
                )
        }
    }

    private fun item(
        expenseId: Long,
        paidDate: String,
        currency: ExpenseCurrency,
        amount: BigDecimal,
        payerTripMemberId: Long,
        isEditLocked: Boolean,
    ) = ExpenseListResult.Item(
        expenseId = expenseId,
        name = "식사",
        paidDate = LocalDate.parse(paidDate),
        currency = currency,
        amount = amount,
        payerTripMemberId = payerTripMemberId,
        settlementId = null,
        isEditLocked = isEditLocked,
    )

    /** 목록에 필요한 칼럼만 다르게 두고 나머지는 같은 값으로 지출을 바로 넣습니다. 결제자와 기록자는 관리자입니다. */
    private fun insertExpense(
        trip: TripFixture,
        paidDate: String,
        currency: String = "KRW",
        amount: Long = 12000,
        deletedAt: Instant? = null,
        editLockExpiresAt: Instant? = null,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO expense (trip_id, idempotency_key, name, split_type, original_amount, discount_amount, amount, currency, " +
                    "paid_date, payer_trip_member_id, created_by_trip_member_id, payment_method, exchange_rate, exchange_rate_source, " +
                    "krw_amount, edit_lock_owner_trip_member_id, edit_lock_expires_at, edit_lock_token, deleted_at, " +
                    "created_at, updated_at) " +
                    "VALUES (?, ?, '식사', 'EQUAL', ?, 0, ?, ?, ?::date, ?, ?, 'CARD', 1, 'MANUAL', ?, ?, ?, ?, ?, now(), now()) " +
                    "RETURNING id",
                Long::class.java,
                trip.tripId,
                UUID.randomUUID(),
                amount,
                amount,
                currency,
                paidDate,
                trip.adminId,
                trip.adminId,
                amount,
                editLockExpiresAt?.let { trip.adminId },
                editLockExpiresAt?.let { Timestamp.from(it) },
                editLockExpiresAt?.let { UUID.randomUUID() },
                deletedAt?.let { Timestamp.from(it) },
            ),
        )

    private fun createTrip(): TripFixture {
        val key = UUID.randomUUID()
        val tripId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, invite_code, " +
                        "idempotency_key, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', '2026-11-05', '2026-11-07', 'JP', 'JPY', 4, 'CONFIRMED', ?, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    key.toString().replace("-", "").take(20),
                    key,
                ),
            )
        tripIds.add(tripId)
        val adminMemberId = createMember()
        val memberMemberId = createMember()
        val leftMemberId = createMember()
        return TripFixture(
            tripId = tripId,
            adminMemberId = adminMemberId,
            adminId = addTripMember(tripId, adminMemberId, "민지", "ADMIN", "MEMBER"),
            memberMemberId = memberMemberId,
            leftMemberId = leftMemberId.also { addTripMember(tripId, it, "하준", "MEMBER", "LEFT") },
        ).also { addTripMember(tripId, memberMemberId, "지훈", "MEMBER", "MEMBER") }
    }

    private fun createMember(): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) VALUES ('GOOGLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(id)
        return id
    }

    private fun addTripMember(
        tripId: Long,
        memberId: Long?,
        tripNickname: String,
        role: String,
        status: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?::trip_member_role, ?::trip_member_status, now(), now()) RETURNING id",
                Long::class.java,
                tripId,
                memberId,
                tripNickname,
                role,
                status,
            ),
        )

    private data class TripFixture(
        val tripId: Long,
        val adminMemberId: Long,
        val adminId: Long,
        val memberMemberId: Long,
        val leftMemberId: Long,
    )
}
