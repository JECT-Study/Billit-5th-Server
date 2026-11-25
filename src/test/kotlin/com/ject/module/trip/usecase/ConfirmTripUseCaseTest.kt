package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.module.trip.domain.TripConfirmationResult
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripStatus
import com.ject.module.trip.domain.UpdateTripCommand
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 확정, 총인원 변경과 인원이 바뀌는 요청이 동시에 들어올 때 여행 행 잠금으로 정원과 확정 조건을 지키는지 실제 PostgreSQL에서 확인합니다. */
class ConfirmTripUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var confirmTripUseCase: ConfirmTripUseCase

    @Autowired
    private lateinit var updateTripUseCase: UpdateTripUseCase

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

        test("빈자리 하나가 남은 여행에 확정, 참여와 비로그인 참여자 등록이 동시에 들어오면 정원을 넘지 않고 확정 결과가 저장된 상태와 같다") {
            // given
            val trip = createTrip(memberCapacity = 3, registeredGuests = 1)
            val joiningMemberId = createMember()

            // when
            val outcomes =
                runConcurrently(REQUEST_COUNT) { index ->
                    when (index) {
                        0 -> joinTripUseCase.join(joiningMemberId, trip.inviteCode, "지훈")
                        1 -> registerTripGuestUseCase.register(trip.adminMemberId, trip.id, "하린")
                        else -> confirmTripUseCase.confirm(trip.adminMemberId, trip.id)
                    }
                }

            // then
            val addFailures = outcomes.take(2).filterIsInstance<BusinessException>().map { it.errorCode }
            val confirmedStatuses = outcomes.drop(2).map { (it as TripConfirmationResult).status }
            assertSoftly {
                countRegisteredMembers(trip.id) shouldBe 3
                addFailures.size shouldBe 1
                addFailures.single() shouldBeIn listOf(TripErrorCode.CAPACITY_FULL, TripErrorCode.ALREADY_CONFIRMED)
                findStatus(trip.id) shouldBe if (TripStatus.CONFIRMED in confirmedStatuses) "CONFIRMED" else "UNCONFIRMED"
            }
        }

        test("등록 인원과 같게 총인원을 줄이는 요청과 참여가 동시에 들어오면 한쪽만 처리되고 등록 인원이 총인원을 넘지 않는다") {
            // given
            val trip = createTrip(memberCapacity = 3, registeredGuests = 1)
            val joiningMemberId = createMember()
            val command =
                UpdateTripCommand(
                    name = null,
                    countryCode = null,
                    customCountryName = null,
                    baseCurrency = null,
                    startDate = null,
                    endDate = null,
                    memberCapacity = 2,
                )

            // when
            val outcomes =
                runConcurrently(2) { index ->
                    if (index == 0) {
                        updateTripUseCase.update(trip.adminMemberId, trip.id, command)
                    } else {
                        joinTripUseCase.join(joiningMemberId, trip.inviteCode, "지훈")
                    }
                }

            // then
            assertSoftly {
                outcomes.count { it is Throwable } shouldBe 1
                outcomes.filterIsInstance<BusinessException>().single().errorCode shouldBeIn
                    listOf(TripErrorCode.CAPACITY_FULL, TripErrorCode.CAPACITY_CHANGE_NOT_ALLOWED)
                countRegisteredMembers(trip.id) shouldBeLessThanOrEqual findMemberCapacity(trip.id)
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

    /** 관리자와 비로그인 참여자 [registeredGuests]명이 등록된 확정 전 여행을 저장합니다. */
    private fun createTrip(
        memberCapacity: Int,
        registeredGuests: Int,
    ): SavedTrip {
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
        repeat(registeredGuests) { index ->
            jdbc.update(
                "INSERT INTO trip_member (trip_id, trip_nickname, role, status, created_at, updated_at) " +
                    "VALUES (?, ?, 'MEMBER', 'GUEST', now(), now())",
                tripId,
                "비회원$index",
            )
        }
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

    private fun countRegisteredMembers(tripId: Long): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM trip_member WHERE trip_id = ? AND status IN ('MEMBER', 'GUEST')",
                Int::class.java,
                tripId,
            ),
        )

    private fun findMemberCapacity(tripId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT member_capacity FROM trip WHERE id = ?", Int::class.java, tripId))

    private fun findStatus(tripId: Long): String? =
        jdbc.queryForObject("SELECT status::text FROM trip WHERE id = ?", String::class.java, tripId)

    companion object {
        private const val REQUEST_COUNT = 5
    }
}
