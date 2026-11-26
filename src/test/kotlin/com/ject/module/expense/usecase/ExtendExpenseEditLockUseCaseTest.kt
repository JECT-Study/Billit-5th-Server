package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class ExtendExpenseEditLockUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var extendExpenseEditLockUseCase: ExtendExpenseEditLockUseCase

    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM expense WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM settlement WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("같은 토큰으로 연장하면 기존 만료 시각에 2분을 더해 저장하고 그 값을 반환하며 지출의 수정 시각은 바꾸지 않는다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val expiresAt = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS)
            val expenseId = insertExpense(trip, lock = Lock(trip.recorderId, token, expiresAt))
            val before = findLock(expenseId)

            // when
            val extendedExpiresAt = extendExpenseEditLockUseCase.extend(trip.recorderMemberId, trip.tripId, expenseId, token)

            // then
            assertSoftly {
                extendedExpiresAt shouldBe expiresAt.plus(Duration.ofMinutes(2))
                findLock(expenseId) shouldBe before + ("edit_lock_expires_at" to Timestamp.from(expiresAt.plus(Duration.ofMinutes(2))))
            }
        }

        test("만료 전에 연장을 두 번 요청하면 요청할 때마다 2분씩 늘어난다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val expiresAt = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS)
            val expenseId = insertExpense(trip, lock = Lock(trip.recorderId, token, expiresAt))

            // when
            val results = List(2) { extendExpenseEditLockUseCase.extend(trip.recorderMemberId, trip.tripId, expenseId, token) }

            // then
            assertSoftly {
                results shouldBe listOf(expiresAt.plus(Duration.ofMinutes(2)), expiresAt.plus(Duration.ofMinutes(4)))
                findLock(expenseId)["edit_lock_expires_at"] shouldBe Timestamp.from(expiresAt.plus(Duration.ofMinutes(4)))
            }
        }

        test("토큰이나 소유자가 다르거나 잠금이 만료됐거나 없으면 409 EXPENSE_EDIT_LOCK_INVALID로 거절하고 잠금을 되살리거나 바꾸지 않는다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val locked = insertExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)))
            val expired = insertExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().minusSeconds(1)))
            val unlocked = insertExpense(trip)
            val requests =
                mapOf(
                    "다른 토큰" to Triple(trip.recorderMemberId, locked, UUID.randomUUID()),
                    "다른 소유자" to Triple(trip.adminMemberId, locked, token),
                    "만료된 잠금" to Triple(trip.recorderMemberId, expired, token),
                    "잠금 없음" to Triple(trip.recorderMemberId, unlocked, token),
                )
            val before = requests.mapValues { findLock(it.value.second) }

            // when
            val failures =
                requests.mapValues { (_, request) ->
                    shouldThrow<BusinessException> {
                        extendExpenseEditLockUseCase.extend(request.first, trip.tripId, request.second, request.third)
                    }.errorCode
                }

            // then
            assertSoftly {
                failures shouldBe requests.mapValues { ExpenseErrorCode.EDIT_LOCK_INVALID }
                requests.mapValues { findLock(it.value.second) } shouldBe before
            }
        }
    }

    private fun findLock(expenseId: Long): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT edit_lock_owner_trip_member_id, edit_lock_token, edit_lock_expires_at, updated_at FROM expense WHERE id = ?",
            expenseId,
        )

    private data class Lock(
        val ownerTripMemberId: Long,
        val token: UUID,
        val expiresAt: Instant,
    )

    /** 기록자는 [TripFixture.recorderId], 결제자는 [TripFixture.payerId]인 지출을 바로 넣습니다. 수정 시각은 1시간 전으로 둡니다. */
    private fun insertExpense(
        trip: TripFixture,
        lock: Lock? = null,
        settlementId: Long? = null,
        deleted: Boolean = false,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO expense (trip_id, idempotency_key, name, split_type, original_amount, discount_amount, amount, currency, " +
                    "paid_date, payer_trip_member_id, created_by_trip_member_id, payment_method, exchange_rate, exchange_rate_source, " +
                    "krw_amount, edit_lock_owner_trip_member_id, edit_lock_token, edit_lock_expires_at, settlement_id, deleted_at, " +
                    "created_at, updated_at) " +
                    "VALUES (?, ?, '식사', 'EQUAL', 12000, 0, 12000, 'KRW', '2026-11-05', ?, ?, 'CARD', 1, 'MANUAL', 12000, ?, ?, ?, ?, " +
                    "?, now() - interval '1 hour', now() - interval '1 hour') RETURNING id",
                Long::class.java,
                trip.tripId,
                UUID.randomUUID(),
                trip.payerId,
                trip.recorderId,
                lock?.ownerTripMemberId,
                lock?.token,
                lock?.let { Timestamp.from(it.expiresAt) },
                settlementId,
                if (deleted) Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))) else null,
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
        val recorderMemberId = createMember()
        val payerMemberId = createMember()
        val leftMemberId = createMember()
        addTripMember(tripId, leftMemberId, "하준", "MEMBER", "LEFT")
        return TripFixture(
            tripId = tripId,
            adminMemberId = adminMemberId,
            adminId = addTripMember(tripId, adminMemberId, "민지", "ADMIN", "MEMBER"),
            recorderMemberId = recorderMemberId,
            recorderId = addTripMember(tripId, recorderMemberId, "지훈", "MEMBER", "MEMBER"),
            payerMemberId = payerMemberId,
            payerId = addTripMember(tripId, payerMemberId, "서연", "MEMBER", "MEMBER"),
            leftMemberId = leftMemberId,
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
        val recorderMemberId: Long,
        val recorderId: Long,
        val payerMemberId: Long,
        val payerId: Long,
        val leftMemberId: Long,
    )
}
