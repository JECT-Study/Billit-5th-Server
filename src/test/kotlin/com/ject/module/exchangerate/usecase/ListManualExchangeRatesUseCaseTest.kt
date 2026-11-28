package com.ject.module.exchangerate.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.exchangerate.domain.ExchangeCurrency
import com.ject.module.exchangerate.domain.ExchangeMethod
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class ListManualExchangeRatesUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var listManualExchangeRatesUseCase: ListManualExchangeRatesUseCase

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

        test("요청자가 그 여행에 등록한 기록만 반환하고 다른 참여자와 다른 여행의 기록은 빼며 응답 값을 그대로 담는다") {
            // given
            val trip = createTrip()
            val otherTrip = createTrip()
            val mine = insertRate(trip, trip.memberId, "JPY", "9.12", "2026-11-05", "CASH", "2026-11-05T01:00:00Z")
            insertRate(trip, trip.adminId, "JPY", "9.12", "2026-11-05", "CASH", "2026-11-05T02:00:00Z")
            insertRate(otherTrip, otherTrip.memberId, "USD", "1358.73", "2026-11-05", "CASH", "2026-11-05T03:00:00Z")

            // when
            val result = listManualExchangeRatesUseCase.list(trip.memberMemberId, trip.tripId, null, page = 0, size = 20)

            // then
            assertSoftly {
                result.items.map { it.exchangeRateId } shouldBe listOf(mine)
                result.items.single().currency shouldBe "JPY"
                result.items.single().exchangeMethod shouldBe ExchangeMethod.CASH
                result.totalElements shouldBe 1
                result.hasNext shouldBe false
            }
        }

        test("최초 등록 시각의 역순으로 정렬하며 환전 날짜와 수정 시각은 순서에 영향을 주지 않는다") {
            // given
            val trip = createTrip()
            val older = insertRate(trip, trip.memberId, "USD", "1358.73", "2026-11-09", "CASH", "2026-11-05T10:00:00Z")
            val newer = insertRate(trip, trip.memberId, "USD", "1360.00", "2026-11-01", "CASH", "2026-11-05T11:00:00Z")
            jdbc.update("UPDATE manual_exchange_rate SET updated_at = '2026-11-06T00:00:00Z' WHERE id = ?", older)

            // when
            val result = listManualExchangeRatesUseCase.list(trip.memberMemberId, trip.tripId, null, page = 0, size = 20)

            // then
            result.items.map { it.exchangeRateId } shouldBe listOf(newer, older)
        }

        test("등록 시각이 같으면 ID의 역순으로 정렬하고 페이지로 나눈다") {
            // given
            val trip = createTrip()
            val first = insertRate(trip, trip.memberId, "JPY", "9.10", "2026-11-05", "CASH", "2026-11-05T10:00:00Z")
            val second = insertRate(trip, trip.memberId, "JPY", "9.11", "2026-11-05", "CASH", "2026-11-05T10:00:00Z")
            val third = insertRate(trip, trip.memberId, "JPY", "9.12", "2026-11-05", "CASH", "2026-11-05T10:00:00Z")

            // when
            val firstPage = listManualExchangeRatesUseCase.list(trip.memberMemberId, trip.tripId, null, page = 0, size = 2)
            val secondPage = listManualExchangeRatesUseCase.list(trip.memberMemberId, trip.tripId, null, page = 1, size = 2)
            val beyondLastPage = listManualExchangeRatesUseCase.list(trip.memberMemberId, trip.tripId, null, page = 2, size = 2)

            // then
            assertSoftly {
                firstPage.items.map { it.exchangeRateId } shouldBe listOf(third, second)
                firstPage.totalElements shouldBe 3
                firstPage.hasNext shouldBe true
                secondPage.items.map { it.exchangeRateId } shouldBe listOf(first)
                secondPage.hasNext shouldBe false
                beyondLastPage.items shouldBe emptyList()
                beyondLastPage.totalElements shouldBe 3
            }
        }

        test("조회 시작 위치가 Int 범위를 넘는 페이지는 빈 목록을 반환한다") {
            // given
            val trip = createTrip()
            insertRate(trip, trip.memberId, "JPY", "9.12", "2026-11-05", "CASH", "2026-11-05T10:00:00Z")

            // when
            val result = listManualExchangeRatesUseCase.list(trip.memberMemberId, trip.tripId, null, page = Int.MAX_VALUE, size = 100)

            // then
            assertSoftly {
                result.items shouldBe emptyList()
                result.totalElements shouldBe 1
                result.hasNext shouldBe false
            }
        }

        test("currency를 지정하면 요청자가 그 통화로 등록한 기록만 최신 등록순으로 반환하고 전체 건수도 그 기록만 센다") {
            // given
            val trip = createTrip()
            insertRate(trip, trip.memberId, "USD", "1358.73", "2026-11-05", "CASH", "2026-11-05T10:00:00Z")
            insertRate(trip, trip.memberId, "JPY", "9.12", "2026-11-05", "CASH", "2026-11-05T12:00:00Z")
            val usdNew = insertRate(trip, trip.memberId, "USD", "1360.00", "2026-11-05", "TRAVEL_CARD", "2026-11-05T11:00:00Z")
            insertRate(trip, trip.adminId, "USD", "1400.00", "2026-11-05", "CASH", "2026-11-05T13:00:00Z")

            // when
            val result = listManualExchangeRatesUseCase.list(trip.memberMemberId, trip.tripId, ExchangeCurrency.USD, page = 0, size = 1)

            // then
            assertSoftly {
                result.items.map { it.exchangeRateId } shouldBe listOf(usdNew)
                result.totalElements shouldBe 2
                result.hasNext shouldBe true
            }
        }

        test("지정한 통화의 기록이 없거나 다른 참여자의 기록만 있으면 빈 목록을 반환한다") {
            // given
            val trip = createTrip()
            insertRate(trip, trip.memberId, "JPY", "9.12", "2026-11-05", "CASH", "2026-11-05T10:00:00Z")
            insertRate(trip, trip.adminId, "USD", "1358.73", "2026-11-05", "CASH", "2026-11-05T11:00:00Z")

            // when
            val result = listManualExchangeRatesUseCase.list(trip.memberMemberId, trip.tripId, ExchangeCurrency.USD, page = 0, size = 20)

            // then
            assertSoftly {
                result.items shouldBe emptyList()
                result.totalElements shouldBe 0
                result.hasNext shouldBe false
            }
        }

        test("최근 기록을 삭제하면 남은 기록 중 등록 시각이 가장 늦은 기록이 첫 항목이 된다") {
            // given
            val trip = createTrip()
            val older = insertRate(trip, trip.memberId, "USD", "1358.73", "2026-11-05", "CASH", "2026-11-05T10:00:00Z")
            val newest = insertRate(trip, trip.memberId, "USD", "1360.00", "2026-11-05", "CASH", "2026-11-05T11:00:00Z")
            jdbc.update("DELETE FROM manual_exchange_rate WHERE id = ?", newest)

            // when
            val result = listManualExchangeRatesUseCase.list(trip.memberMemberId, trip.tripId, ExchangeCurrency.USD, page = 0, size = 20)

            // then
            result.items.map { it.exchangeRateId } shouldBe listOf(older)
        }

        test("등록 시각이 같은 같은 통화 기록은 ID의 역순으로 반환한다") {
            // given
            val trip = createTrip()
            val first = insertRate(trip, trip.memberId, "EUR", "1500.10", "2026-11-05", "CASH", "2026-11-05T10:00:00Z")
            val second = insertRate(trip, trip.memberId, "EUR", "1500.20", "2026-11-05", "CASH", "2026-11-05T10:00:00Z")

            // when
            val result = listManualExchangeRatesUseCase.list(trip.memberMemberId, trip.tripId, ExchangeCurrency.EUR, page = 0, size = 20)

            // then
            result.items.map { it.exchangeRateId } shouldBe listOf(second, first)
        }

        test("여행이 없으면 404로 거절한다") {
            // given
            val trip = createTrip()

            // when
            val exception =
                shouldThrow<BusinessException> { listManualExchangeRatesUseCase.list(trip.memberMemberId, Long.MAX_VALUE, null, 0, 20) }

            // then
            exception.errorCode shouldBe CommonErrorCode.NOT_FOUND
        }

        test("여행 참여자가 아니거나 여행을 나간 회원이면 403으로 거절한다") {
            // given
            val trip = createTrip()
            val outsiderMemberId = createMember()

            // when
            val outsider =
                shouldThrow<BusinessException> { listManualExchangeRatesUseCase.list(outsiderMemberId, trip.tripId, null, 0, 20) }
            val left = shouldThrow<BusinessException> { listManualExchangeRatesUseCase.list(trip.leftMemberId, trip.tripId, null, 0, 20) }

            // then
            assertSoftly {
                outsider.errorCode shouldBe CommonErrorCode.FORBIDDEN
                left.errorCode shouldBe CommonErrorCode.FORBIDDEN
            }
        }
    }

    private fun insertRate(
        trip: TripFixture,
        tripMemberId: Long,
        currency: String,
        rate: String,
        exchangeDate: String,
        method: String,
        createdAt: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO manual_exchange_rate (trip_id, currency, rate, exchange_date, exchange_method, created_by_trip_member_id, " +
                    "created_at, updated_at) VALUES (?, ?, ?::numeric, ?::date, ?::exchange_method, ?, ?::timestamptz, ?::timestamptz) RETURNING id",
                Long::class.java,
                trip.tripId,
                currency,
                rate,
                exchangeDate,
                method,
                tripMemberId,
                createdAt,
                createdAt,
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
