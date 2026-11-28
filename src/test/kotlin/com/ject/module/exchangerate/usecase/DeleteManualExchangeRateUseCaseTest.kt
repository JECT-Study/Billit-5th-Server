package com.ject.module.exchangerate.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class DeleteManualExchangeRateUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var deleteManualExchangeRateUseCase: DeleteManualExchangeRateUseCase

    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM manual_exchange_rate WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("등록자가 삭제하면 그 기록만 지우고 다른 기록은 남긴다") {
            // given
            val trip = createTrip()
            val target = insertRate(trip, trip.memberId, "JPY", "9.12")
            val remaining = insertRate(trip, trip.memberId, "USD", "1358.73")

            // when
            deleteManualExchangeRateUseCase.delete(trip.memberMemberId, trip.tripId, target)

            // then
            remainingIds(trip) shouldBe listOf(remaining)
        }

        test("다른 참여자가 등록한 기록, 이미 삭제한 기록과 다른 여행의 기록은 404이고 아무것도 지우지 않는다") {
            // given
            val trip = createTrip()
            val otherTrip = createTrip()
            val others = insertRate(trip, trip.adminId, "JPY", "9.12")
            val inOtherTrip = insertRate(otherTrip, otherTrip.memberId, "JPY", "9.12")
            val deleted = insertRate(trip, trip.memberId, "USD", "1358.73")
            deleteManualExchangeRateUseCase.delete(trip.memberMemberId, trip.tripId, deleted)

            // when
            val failures =
                listOf(others, deleted, inOtherTrip, Long.MAX_VALUE).map {
                    shouldThrow<BusinessException> { deleteManualExchangeRateUseCase.delete(trip.memberMemberId, trip.tripId, it) }
                }

            // then
            assertSoftly {
                failures.all { it.errorCode == CommonErrorCode.NOT_FOUND } shouldBe true
                remainingIds(trip) shouldBe listOf(others)
                remainingIds(otherTrip) shouldBe listOf(inOtherTrip)
            }
        }

        test("같은 기록의 삭제를 동시에 요청하면 한 요청만 삭제하고 나머지는 404이다") {
            // given
            val trip = createTrip()
            val target = insertRate(trip, trip.memberId, "JPY", "9.12")
            val threads = 8
            val executor = Executors.newFixedThreadPool(threads)
            val ready = CountDownLatch(threads)
            val start = CountDownLatch(1)

            // when
            val futures =
                (1..threads).map {
                    executor.submit<Throwable?> {
                        ready.countDown()
                        start.await()
                        runCatching { deleteManualExchangeRateUseCase.delete(trip.memberMemberId, trip.tripId, target) }.exceptionOrNull()
                    }
                }
            ready.await()
            start.countDown()
            val failures = futures.map { it.get() }
            executor.shutdown()

            // then
            assertSoftly {
                failures.count { it == null } shouldBe 1
                failures.filterNotNull().all { it is BusinessException && it.errorCode == CommonErrorCode.NOT_FOUND } shouldBe true
                remainingIds(trip) shouldBe emptyList()
            }
        }

        test("없는 여행은 404, 참여자가 아니거나 나간 회원은 403이다") {
            // given
            val trip = createTrip()
            val target = insertRate(trip, trip.memberId, "JPY", "9.12")

            // when
            val missing =
                shouldThrow<BusinessException> { deleteManualExchangeRateUseCase.delete(trip.memberMemberId, Long.MAX_VALUE, target) }
            val outsider = shouldThrow<BusinessException> { deleteManualExchangeRateUseCase.delete(createMember(), trip.tripId, target) }
            val left = shouldThrow<BusinessException> { deleteManualExchangeRateUseCase.delete(trip.leftMemberId, trip.tripId, target) }

            // then
            assertSoftly {
                missing.errorCode shouldBe CommonErrorCode.NOT_FOUND
                outsider.errorCode shouldBe CommonErrorCode.FORBIDDEN
                left.errorCode shouldBe CommonErrorCode.FORBIDDEN
                remainingIds(trip) shouldBe listOf(target)
            }
        }
    }

    private fun remainingIds(trip: TripFixture): List<Long> =
        jdbc
            .queryForList(
                "SELECT id FROM manual_exchange_rate WHERE trip_id = ? ORDER BY id",
                Long::class.java,
                trip.tripId,
            ).filterNotNull()

    private fun insertRate(
        trip: TripFixture,
        tripMemberId: Long,
        currency: String,
        rate: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO manual_exchange_rate (trip_id, currency, rate, exchange_date, exchange_method, created_by_trip_member_id, " +
                    "created_at, updated_at) VALUES (?, ?, ?::numeric, '2026-11-05'::date, 'CASH'::exchange_method, ?, now(), now()) RETURNING id",
                Long::class.java,
                trip.tripId,
                currency,
                rate,
                tripMemberId,
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
        val adminId = addTripMember(tripId, adminMemberId, "민지", "ADMIN", "MEMBER")
        val memberId = addTripMember(tripId, memberMemberId, "지훈", "MEMBER", "MEMBER")
        addTripMember(tripId, leftMemberId, "하준", "MEMBER", "LEFT")
        return TripFixture(tripId, memberMemberId, leftMemberId, adminId, memberId)
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
        val memberMemberId: Long,
        val leftMemberId: Long,
        /** 관리자의 여행 참여자 ID입니다. */
        val adminId: Long,
        /** [memberMemberId] 회원의 여행 참여자 ID입니다. */
        val memberId: Long,
    )
}
