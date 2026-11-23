package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripMembershipResult
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JoinTripUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var joinTripUseCase: JoinTripUseCase

    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("빈자리가 하나 남은 여행에 여러 회원이 동시에 참여하면 한 명만 등록되고 나머지는 TRIP_CAPACITY_FULL을 받는다") {
            // given
            val (tripId, inviteCode) = createTrip(memberCapacity = 2)
            val memberIdsToJoin = List(REQUEST_COUNT) { createMember() }

            // when
            val outcomes =
                runConcurrently(REQUEST_COUNT) { index ->
                    joinTripUseCase.join(memberIdsToJoin[index], inviteCode, "참여자$index")
                }

            // then
            assertSoftly {
                outcomes.count { it is TripMembershipResult } shouldBe 1
                outcomes.filterIsInstance<BusinessException>().map { it.errorCode } shouldBe
                    List(REQUEST_COUNT - 1) { TripErrorCode.CAPACITY_FULL }
                countTripMembers(tripId) shouldBe 2
            }
        }

        test("같은 회원이 동시에 여러 번 참여해도 참여 정보는 하나만 만들고 모든 요청이 같은 tripMemberId를 받는다") {
            // given
            val (tripId, inviteCode) = createTrip(memberCapacity = 3)
            val memberId = createMember()

            // when
            val outcomes = runConcurrently(REQUEST_COUNT) { joinTripUseCase.join(memberId, inviteCode, "지훈") }

            // then
            val results = outcomes.filterIsInstance<TripMembershipResult>()
            assertSoftly {
                results.size shouldBe REQUEST_COUNT
                results.map { it.tripMemberId }.toSet() shouldBe setOf(findTripMemberId(tripId, memberId))
                results.count { it.isNewMember } shouldBe 1
                countTripMembers(tripId) shouldBe 2
            }
        }
    }

    /** 요청을 한꺼번에 시작하고, 각 요청의 반환값이나 던진 예외를 순서대로 돌려줍니다. */
    private fun runConcurrently(
        count: Int,
        action: (Int) -> Any,
    ): List<Any> {
        val barrier = CyclicBarrier(count)
        val executor = Executors.newFixedThreadPool(count)
        try {
            val futures =
                List(count) { index ->
                    executor.submit<Any> {
                        barrier.await(10, TimeUnit.SECONDS)
                        action(index)
                    }
                }
            return futures.map { future ->
                try {
                    future.get(30, TimeUnit.SECONDS)
                } catch (exception: ExecutionException) {
                    checkNotNull(exception.cause)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /** 관리자 한 명이 참여한 확정 전 여행을 저장하고 여행 ID와 초대 코드를 반환합니다. */
    private fun createTrip(memberCapacity: Int): Pair<Long, String> {
        val inviteCode =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(6)
                .uppercase()
        val tripId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, " +
                        "invite_code, idempotency_key, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', DATE '2026-11-05', DATE '2026-11-07', 'JP', 'JPY', ?, 'UNCONFIRMED', ?, ?, now(), now()) " +
                        "RETURNING id",
                    Long::class.java,
                    memberCapacity,
                    inviteCode,
                    UUID.randomUUID(),
                ),
            )
        tripIds.add(tripId)
        jdbc.update(
            "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, created_at, updated_at) " +
                "VALUES (?, ?, '민지', 'ADMIN', now(), now())",
            tripId,
            createMember(),
        )
        return tripId to inviteCode
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

    private fun findTripMemberId(
        tripId: Long,
        memberId: Long,
    ): Long? = jdbc.queryForObject("SELECT id FROM trip_member WHERE trip_id = ? AND member_id = ?", Long::class.java, tripId, memberId)

    private fun countTripMembers(tripId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM trip_member WHERE trip_id = ?", Int::class.java, tripId))

    companion object {
        private const val REQUEST_COUNT = 5
    }
}
