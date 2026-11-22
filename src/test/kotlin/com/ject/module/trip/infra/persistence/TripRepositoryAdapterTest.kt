package com.ject.module.trip.infra.persistence

import com.ject.module.trip.domain.Trip
import com.ject.module.trip.domain.TripCountryCode
import com.ject.module.trip.domain.TripCurrency
import com.ject.module.trip.domain.TripMember
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripStatus
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDate
import java.util.UUID

class TripRepositoryAdapterTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var tripRepository: TripRepository

    private val idempotencyKeys = mutableListOf<UUID>()

    init {
        afterEach {
            idempotencyKeys.forEach { key ->
                jdbc.update("DELETE FROM trip_member WHERE trip_id IN (SELECT id FROM trip WHERE idempotency_key = ?)", key)
                jdbc.update("DELETE FROM trip WHERE idempotency_key = ?", key)
            }
            idempotencyKeys.clear()
        }

        test("여행 저장이 고유키나 초대 코드 중복이 아닌 무결성 오류로 실패하면 오류를 그대로 던진다") {
            // given
            val trip = trip(name = "가".repeat(16))

            // when
            // 고유키나 초대 코드 중복이면 DataIntegrityViolationException이 아닌 TripCreationConflictException으로 바뀝니다.
            val failure = shouldThrow<DataIntegrityViolationException> { tripRepository.saveTrip(trip) }

            // then
            assertSoftly {
                violatedConstraint(failure) shouldBe null
                countTrips(trip.idempotencyKey) shouldBe 0
            }
        }

        test("종료일이 시작일보다 빠르거나 정원이 1~10명을 벗어난 여행은 저장되지 않는다") {
            // given
            val trips =
                mapOf(
                    "시작일보다 빠른 종료일" to trip(startDate = LocalDate.parse("2026-11-05"), endDate = LocalDate.parse("2026-11-04")),
                    "정원 0명" to trip(memberCapacity = 0),
                    "정원 11명" to trip(memberCapacity = 11),
                )

            // when
            val failures = trips.mapValues { shouldThrow<DataIntegrityViolationException> { tripRepository.saveTrip(it.value) } }

            // then
            assertSoftly {
                failures.mapValues { violatedConstraint(it.value) } shouldBe
                    mapOf(
                        "시작일보다 빠른 종료일" to "ck_trip_valid_date_range",
                        "정원 0명" to "ck_trip_member_capacity_range",
                        "정원 11명" to "ck_trip_member_capacity_range",
                    )
                trips.mapValues { countTrips(it.value.idempotencyKey) } shouldBe trips.mapValues { 0 }
            }
        }

        test("한 여행에 관리자를 두 명 저장할 수 없다") {
            // given
            val tripId = tripRepository.saveTrip(trip())
            tripRepository.saveTripMember(tripMember(tripId, tripNickname = "민지"))

            // when
            val failure =
                shouldThrow<DataIntegrityViolationException> { tripRepository.saveTripMember(tripMember(tripId, tripNickname = "지훈")) }

            // then
            assertSoftly {
                violatedConstraint(failure) shouldBe "uq_trip_member_trip_id_admin"
                jdbc.queryForList("SELECT trip_nickname FROM trip_member WHERE trip_id = ?", String::class.java, tripId) shouldBe
                    listOf("민지")
            }
        }
    }

    private fun trip(
        name: String = "도쿄 여행",
        startDate: LocalDate = LocalDate.parse("2026-11-05"),
        endDate: LocalDate = LocalDate.parse("2026-11-07"),
        memberCapacity: Int = 3,
    ): Trip {
        val idempotencyKey = UUID.randomUUID()
        idempotencyKeys.add(idempotencyKey)
        return Trip(
            name = name,
            startDate = startDate,
            endDate = endDate,
            countryCode = TripCountryCode.JP,
            customCountryName = null,
            baseCurrency = TripCurrency.JPY,
            memberCapacity = memberCapacity,
            status = TripStatus.UNCONFIRMED,
            confirmedAt = null,
            inviteCode = idempotencyKey.toString().take(6).uppercase(),
            idempotencyKey = idempotencyKey,
        )
    }

    /** 회원 행이 없어도 저장할 수 있도록 비로그인 참여자 형태의 관리자 행을 만듭니다. 관리자 수 제약만 확인합니다. */
    private fun tripMember(
        tripId: Long,
        tripNickname: String,
    ): TripMember = TripMember(tripId = tripId, memberId = null, tripNickname = tripNickname, role = TripMemberRole.ADMIN, joinedAt = null)

    private fun countTrips(idempotencyKey: UUID): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM trip WHERE idempotency_key = ?", Int::class.java, idempotencyKey))

    private fun violatedConstraint(exception: DataIntegrityViolationException): String? =
        generateSequence(exception.cause) { it.cause }
            .filterIsInstance<org.hibernate.exception.ConstraintViolationException>()
            .firstOrNull()
            ?.constraintName
}
