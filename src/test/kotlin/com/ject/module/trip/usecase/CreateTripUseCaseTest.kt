package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.trip.domain.CreateTripCommand
import com.ject.module.trip.domain.TripCountryCode
import com.ject.module.trip.domain.TripCurrency
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripSummaryResult
import com.ject.module.trip.implement.TripInviteCodeGenerator
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CreateTripUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var createTripUseCase: CreateTripUseCase

    @MockitoSpyBean
    private lateinit var tripRepository: TripRepository

    @MockitoSpyBean
    private lateinit var tripInviteCodeGenerator: TripInviteCodeGenerator

    private val memberIds = mutableListOf<Long>()
    private val idempotencyKeys = mutableListOf<UUID>()

    init {
        afterEach {
            idempotencyKeys.forEach { key ->
                jdbc.update("DELETE FROM trip_member WHERE trip_id IN (SELECT id FROM trip WHERE idempotency_key = ?)", key)
                jdbc.update("DELETE FROM trip WHERE idempotency_key = ?", key)
            }
            idempotencyKeys.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("같은 Idempotency-Key의 요청이 동시에 와도 여행과 생성자 참여 정보는 한 번만 만들고 모든 요청이 같은 여행을 받는다") {
            // given
            val memberId = createMember()
            val idempotencyKey = newIdempotencyKey()
            // 두 요청이 모두 기존 여행 없음을 확인한 뒤에 저장하도록 첫 조회 두 번을 맞춥니다.
            val barrier = CyclicBarrier(2)
            val firstLookups = AtomicInteger()
            doAnswer { invocation ->
                val result = invocation.callRealMethod()
                if (firstLookups.incrementAndGet() <= 2) {
                    barrier.await(10, TimeUnit.SECONDS)
                }
                result
            }.`when`(tripRepository).findByIdempotencyKey(idempotencyKey)
            val executor = Executors.newFixedThreadPool(2)

            // when
            val results =
                try {
                    List(2) { executor.submit<TripSummaryResult> { createTripUseCase.create(memberId, idempotencyKey, command()) } }
                        .map { it.get(30, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }

            // then
            val tripIds = findTripIds(idempotencyKey)
            assertSoftly {
                // 세 번째 조회는 늦은 요청이 고유키 유니크 제약 위반으로 롤백된 뒤 먼저 저장된 여행을 다시 찾는 조회입니다.
                firstLookups.get() shouldBe 3
                tripIds.size shouldBe 1
                results.map { it.tripId } shouldBe List(2) { tripIds.single() }
                results[0] shouldBe results[1]
                countTripMembers(tripIds.single()) shouldBe 1
            }
        }

        test("생성자 참여 정보 저장이 실패하면 여행과 고유키도 저장하지 않고, 같은 키로 다시 요청하면 여행을 만든다") {
            // given
            val memberId = createMember()
            val idempotencyKey = newIdempotencyKey()
            // trip_member.trip_nickname은 10자까지라 11자 닉네임은 여행을 저장한 뒤 참여자 저장에서 실패합니다.
            val failingCommand = command(tripNickname = "가".repeat(11))

            // when
            val failure =
                shouldThrow<DataIntegrityViolationException> { createTripUseCase.create(memberId, idempotencyKey, failingCommand) }
            val tripIdsAfterFailure = findTripIds(idempotencyKey)
            val retried = createTripUseCase.create(memberId, idempotencyKey, command())

            // then
            assertSoftly {
                failure.message.orEmpty().contains("trip_member") shouldBe true
                tripIdsAfterFailure shouldBe emptyList()
                countMemberParticipations(memberId) shouldBe 1
                findTripIds(idempotencyKey) shouldBe listOf(retried.tripId)
            }
        }

        test("같은 회원이 같은 Idempotency-Key로 다시 요청하면 나간 회원과 탈퇴 회원을 빼고 등록 인원을 센다") {
            // given
            val memberId = createMember()
            val leftMemberId = createMember()
            val idempotencyKey = newIdempotencyKey()
            val created = createTripUseCase.create(memberId, idempotencyKey, command())
            addTripMember(created.tripId, memberId = null, tripNickname = "서연", status = "GUEST")
            addTripMember(created.tripId, memberId = leftMemberId, tripNickname = "하준", status = "LEFT")
            addTripMember(created.tripId, memberId = null, tripNickname = "도윤", status = "WITHDRAWN")

            // when
            val retried = createTripUseCase.create(memberId, idempotencyKey, command())

            // then
            assertSoftly {
                retried.tripId shouldBe created.tripId
                retried.registeredMemberCount shouldBe 2
            }
        }

        test("초대 코드가 다른 여행과 겹치면 새 코드로 여행을 만든다") {
            // given
            val memberId = createMember()
            val existingKey = newIdempotencyKey()
            val newKey = newIdempotencyKey()
            doReturn("DUPA01", "DUPA01", "NEWB02").`when`(tripInviteCodeGenerator).generate()
            createTripUseCase.create(memberId, existingKey, command())

            // when
            val result = createTripUseCase.create(memberId, newKey, command())

            // then
            assertSoftly {
                findInviteCode(existingKey) shouldBe "DUPA01"
                findInviteCode(newKey) shouldBe "NEWB02"
                findTripIds(newKey) shouldBe listOf(result.tripId)
                countTripMembers(result.tripId) shouldBe 1
            }
        }

        test("종료일이 시작일보다 빠르거나 대표 국가와 기타 국가명이 맞지 않으면 여행을 만들지 않는다") {
            // given
            val memberId = createMember()
            val commands =
                mapOf(
                    "시작일보다 빠른 종료일" to command(startDate = LocalDate.parse("2026-11-05"), endDate = LocalDate.parse("2026-11-04")),
                    "기타 국가인데 국가명 없음" to command(countryCode = TripCountryCode.ETC, customCountryName = null),
                    "기타 국가가 아닌데 국가명 있음" to command(countryCode = TripCountryCode.JP, customCountryName = "베트남"),
                )
            val keys = commands.mapValues { newIdempotencyKey() }

            // when
            val failures =
                commands.mapValues { (case, command) ->
                    shouldThrow<BusinessException> { createTripUseCase.create(memberId, keys.getValue(case), command) }
                }

            // then
            assertSoftly {
                failures.mapValues { it.value.errorCode } shouldBe commands.mapValues { CommonErrorCode.INVALID_REQUEST }
                keys.mapValues { findTripIds(it.value) } shouldBe commands.mapValues { emptyList<Long>() }
                countMemberParticipations(memberId) shouldBe 0
            }
        }
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
        status: String,
    ) {
        jdbc.update(
            "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'MEMBER', ?::trip_member_status, now(), now())",
            tripId,
            memberId,
            tripNickname,
            status,
        )
    }

    private fun newIdempotencyKey(): UUID {
        val idempotencyKey = UUID.randomUUID()
        idempotencyKeys.add(idempotencyKey)
        return idempotencyKey
    }

    private fun command(
        countryCode: TripCountryCode = TripCountryCode.JP,
        customCountryName: String? = null,
        startDate: LocalDate = LocalDate.parse("2026-11-05"),
        endDate: LocalDate = LocalDate.parse("2026-11-07"),
        tripNickname: String = "민지",
    ): CreateTripCommand =
        CreateTripCommand(
            name = "도쿄 여행",
            countryCode = countryCode,
            customCountryName = customCountryName,
            baseCurrency = TripCurrency.JPY,
            startDate = startDate,
            endDate = endDate,
            memberCapacity = 3,
            tripNickname = tripNickname,
        )

    private fun findTripIds(idempotencyKey: UUID): List<Long> =
        jdbc.queryForList("SELECT id FROM trip WHERE idempotency_key = ?", Long::class.java, idempotencyKey).map { checkNotNull(it) }

    private fun findInviteCode(idempotencyKey: UUID): String? =
        jdbc.queryForObject("SELECT invite_code FROM trip WHERE idempotency_key = ?", String::class.java, idempotencyKey)

    private fun countTripMembers(tripId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM trip_member WHERE trip_id = ?", Int::class.java, tripId))

    private fun countMemberParticipations(memberId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM trip_member WHERE member_id = ?", Int::class.java, memberId))
}
