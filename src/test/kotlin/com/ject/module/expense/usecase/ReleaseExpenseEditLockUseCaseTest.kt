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
import java.util.UUID

class ReleaseExpenseEditLockUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var releaseExpenseEditLockUseCase: ReleaseExpenseEditLockUseCase

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

        test("토큰이 같으면 만료 여부와 관계없이 잠금을 비우고 지출의 수정 시각은 바꾸지 않는다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val locked = insertExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)))
            val expired = insertExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().minusSeconds(1)))
            val updatedAts = listOf(locked, expired).map { findLock(it)["updated_at"] }

            // when
            listOf(locked, expired).forEach { releaseExpenseEditLockUseCase.release(trip.recorderMemberId, trip.tripId, it, token) }

            // then
            listOf(locked, expired).map { findLock(it) } shouldBe
                updatedAts.map {
                    mapOf(
                        "edit_lock_owner_trip_member_id" to null,
                        "edit_lock_token" to null,
                        "edit_lock_expires_at" to null,
                        "updated_at" to it,
                    )
                }
        }

        test("잠금이 없거나 남은 잠금이 만료됐으면 같은 해제를 다시 보낸 것으로 보고 성공하며 남은 잠금은 그대로 둔다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val unlocked = insertExpense(trip)
            val otherExpired = insertExpense(trip, lock = Lock(trip.adminId, UUID.randomUUID(), Instant.now().minusSeconds(1)))
            val before = listOf(unlocked, otherExpired).map { findLock(it) }

            // when
            listOf(unlocked, otherExpired).forEach { releaseExpenseEditLockUseCase.release(trip.recorderMemberId, trip.tripId, it, token) }

            // then
            listOf(unlocked, otherExpired).map { findLock(it) } shouldBe before
        }

        test("다시 획득한 새 토큰이나 다른 회원의 유효한 잠금이면 409 EXPENSE_EDIT_LOCK_INVALID로 거절하고 잠금을 유지한다") {
            // given
            val trip = createTrip()
            val oldToken = UUID.randomUUID()
            val newToken = UUID.randomUUID()
            val reacquired = insertExpense(trip, lock = Lock(trip.recorderId, newToken, Instant.now().plusSeconds(60)))
            val requests =
                mapOf(
                    "이전 수정 작업의 토큰" to Pair(trip.recorderMemberId, oldToken),
                    "다른 회원" to Pair(trip.adminMemberId, newToken),
                )
            val before = findLock(reacquired)

            // when
            val failures =
                requests.mapValues { (_, request) ->
                    shouldThrow<BusinessException> {
                        releaseExpenseEditLockUseCase.release(request.first, trip.tripId, reacquired, request.second)
                    }.errorCode
                }

            // then
            assertSoftly {
                failures shouldBe requests.mapValues { ExpenseErrorCode.EDIT_LOCK_INVALID }
                findLock(reacquired) shouldBe before
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
