package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.module.trip.domain.TripErrorCode
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RegisterTripGuestUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var registerTripGuestUseCase: RegisterTripGuestUseCase

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

        test("빈자리가 하나 남은 여행에 비로그인 참여자 등록과 초대 코드 참여가 동시에 들어오면 한 건만 저장되고 나머지는 TRIP_CAPACITY_FULL을 받는다") {
            // given
            val trip = createTrip(memberCapacity = 2)
            val joiningMemberIds = List(REQUEST_COUNT) { createMember() }

            // when
            val outcomes =
                runConcurrently(REQUEST_COUNT * 2) { index ->
                    if (index < REQUEST_COUNT) {
                        registerTripGuestUseCase.register(trip.adminMemberId, trip.id, "비회원$index")
                    } else {
                        joinTripUseCase.join(joiningMemberIds[index - REQUEST_COUNT], trip.inviteCode, "회원$index")
                    }
                }

            // then
            assertSoftly {
                outcomes.count { it !is Throwable } shouldBe 1
                outcomes.filterIsInstance<BusinessException>().map { it.errorCode } shouldBe
                    List(REQUEST_COUNT * 2 - 1) { TripErrorCode.CAPACITY_FULL }
                countTripMembers(trip.id) shouldBe 2
            }
        }

        test("같은 이름의 비로그인 참여자를 동시에 등록하면 한 명만 저장되고 나머지는 TRIP_NICKNAME_DUPLICATED를 받는다") {
            // given
            val trip = createTrip(memberCapacity = 10)

            // when
            val outcomes = runConcurrently(REQUEST_COUNT) { registerTripGuestUseCase.register(trip.adminMemberId, trip.id, "서연") }

            // then
            assertSoftly {
                outcomes.count { it !is Throwable } shouldBe 1
                outcomes.filterIsInstance<BusinessException>().map { it.errorCode } shouldBe
                    List(REQUEST_COUNT - 1) { TripErrorCode.NICKNAME_DUPLICATED }
                countTripMembers(trip.id) shouldBe 2
            }
        }
    }

    private data class SavedTrip(
        val id: Long,
        val inviteCode: String,
        val adminMemberId: Long,
    )

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

    /** 관리자 한 명이 참여한 확정 전 여행을 저장합니다. */
    private fun createTrip(memberCapacity: Int): SavedTrip {
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
        val adminMemberId = createMember()
        jdbc.update(
            "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                "VALUES (?, ?, '민지', 'ADMIN', 'MEMBER', now(), now())",
            tripId,
            adminMemberId,
        )
        return SavedTrip(tripId, inviteCode, adminMemberId)
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

    private fun countTripMembers(tripId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM trip_member WHERE trip_id = ?", Int::class.java, tripId))

    companion object {
        private const val REQUEST_COUNT = 5
    }
}
