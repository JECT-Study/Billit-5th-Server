package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.PaymentMethod
import com.ject.module.expense.domain.RateSource
import com.ject.module.expense.domain.SplitType
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class GetExpenseDetailUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var getExpenseDetailUseCase: GetExpenseDetailUseCase

    @Autowired
    private lateinit var createExpenseUseCase: CreateExpenseUseCase

    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM expense_share_amount WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM expense_item_member WHERE expense_id IN (SELECT id FROM expense WHERE trip_id = ?)", id)
                jdbc.update("DELETE FROM expense_item WHERE expense_id IN (SELECT id FROM expense WHERE trip_id = ?)", id)
                jdbc.update("DELETE FROM expense WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("다른 참여자가 기록한 품목별 지출도 등록할 때와 같은 상세를 품목 표시 순서대로 조회한다") {
            // given
            val trip = createTrip()
            val created = createExpenseUseCase.create(trip.adminMemberId, trip.tripId, UUID.randomUUID(), byItemCommand(trip))

            // when
            val result = getExpenseDetailUseCase.get(trip.memberMemberId, trip.tripId, created.expenseId)

            // then
            assertSoftly {
                result shouldBe created
                result.items.map { it.sortOrder } shouldBe listOf(0, 1)
                result.items.map { it.name } shouldBe listOf("피자", null)
            }
        }

        test("지출이 없거나 삭제됐거나 다른 여행의 지출이면 404, 여행 참여자가 아니면 403으로 거절한다") {
            // given
            val trip = createTrip()
            val otherTrip = createTrip()
            val deleted = createExpenseUseCase.create(trip.adminMemberId, trip.tripId, UUID.randomUUID(), byItemCommand(trip)).expenseId
            jdbc.update("UPDATE expense SET deleted_at = now() WHERE id = ?", deleted)
            val otherTripExpense =
                createExpenseUseCase
                    .create(
                        otherTrip.adminMemberId,
                        otherTrip.tripId,
                        UUID.randomUUID(),
                        byItemCommand(otherTrip),
                    ).expenseId
            val expense = createExpenseUseCase.create(trip.adminMemberId, trip.tripId, UUID.randomUUID(), byItemCommand(trip)).expenseId

            // when
            val failures =
                mapOf(
                    "없는 지출" to Triple(trip.adminMemberId, trip.tripId, Long.MAX_VALUE),
                    "삭제된 지출" to Triple(trip.adminMemberId, trip.tripId, deleted),
                    "다른 여행의 지출" to Triple(trip.adminMemberId, trip.tripId, otherTripExpense),
                    "없는 여행" to Triple(trip.adminMemberId, Long.MAX_VALUE, expense),
                    "다른 여행의 참여자" to Triple(otherTrip.adminMemberId, trip.tripId, expense),
                ).mapValues { (_, request) ->
                    shouldThrow<BusinessException> { getExpenseDetailUseCase.get(request.first, request.second, request.third) }.errorCode
                }

            // then
            failures shouldBe
                mapOf(
                    "없는 지출" to CommonErrorCode.NOT_FOUND,
                    "삭제된 지출" to CommonErrorCode.NOT_FOUND,
                    "다른 여행의 지출" to CommonErrorCode.NOT_FOUND,
                    "없는 여행" to CommonErrorCode.NOT_FOUND,
                    "다른 여행의 참여자" to CommonErrorCode.FORBIDDEN,
                )
        }
    }

    private fun byItemCommand(trip: TripFixture): CreateExpenseCommand =
        CreateExpenseCommand(
            name = "식사",
            splitType = SplitType.ITEM,
            currency = ExpenseCurrency.KRW,
            paidDate = LocalDate.parse("2026-11-05"),
            paymentMethod = PaymentMethod.CARD,
            payerTripMemberId = trip.adminId,
            originalAmount = BigDecimal("12000.00"),
            discountAmount = BigDecimal("0.00"),
            exchangeRate = BigDecimal("1.00"),
            exchangeRateSource = RateSource.MANUAL,
            tripMemberIds = null,
            items =
                listOf(
                    CreateExpenseCommand.Item("피자", BigDecimal("8000.00"), BigDecimal("0.00"), listOf(trip.adminId, trip.memberId)),
                    CreateExpenseCommand.Item(null, BigDecimal("4000.00"), BigDecimal("0.00"), listOf(trip.adminId)),
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
        return TripFixture(
            tripId = tripId,
            adminMemberId = adminMemberId,
            adminId = addTripMember(tripId, adminMemberId, "민지", "ADMIN"),
            memberMemberId = memberMemberId,
            memberId = addTripMember(tripId, memberMemberId, "지훈", "MEMBER"),
        )
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
        memberId: Long,
        tripNickname: String,
        role: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?::trip_member_role, 'MEMBER', now(), now()) RETURNING id",
                Long::class.java,
                tripId,
                memberId,
                tripNickname,
                role,
            ),
        )

    private data class TripFixture(
        val tripId: Long,
        val adminMemberId: Long,
        val adminId: Long,
        val memberMemberId: Long,
        val memberId: Long,
    )
}
