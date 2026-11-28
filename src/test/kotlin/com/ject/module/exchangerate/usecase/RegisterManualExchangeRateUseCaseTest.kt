package com.ject.module.exchangerate.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.exchangerate.domain.ExchangeCurrency
import com.ject.module.exchangerate.domain.ExchangeMethod
import com.ject.module.exchangerate.domain.ExchangeRateErrorCode
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class RegisterManualExchangeRateUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var registerManualExchangeRateUseCase: RegisterManualExchangeRateUseCase

    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()
    private val exchangeDate = LocalDate.parse("2026-11-05")

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

        test("참여 중인 회원이 등록하면 요청자의 여행 참여자를 등록자로 저장하고 등록한 기록을 반환한다") {
            // given
            val trip = createTrip()

            // when
            val result = register(trip, trip.memberMemberId, rate = "9.12")

            // then
            val row = jdbc.queryForMap("SELECT * FROM manual_exchange_rate WHERE id = ?", result.exchangeRateId)
            assertSoftly {
                result.currency shouldBe "JPY"
                result.rate.compareTo(BigDecimal("9.12")) shouldBe 0
                result.exchangeMethod shouldBe ExchangeMethod.CASH
                result.exchangeDate shouldBe exchangeDate
                result.createdAt.shouldNotBeNull()
                row["trip_id"] shouldBe trip.tripId
                row["created_by_trip_member_id"] shouldBe trip.memberId
                row["currency"] shouldBe "JPY"
                (row["rate"] as BigDecimal).compareTo(BigDecimal("9.12")) shouldBe 0
                row["exchange_method"].toString() shouldBe "CASH"
            }
        }

        test("환율이 0이면 400으로 거절하고 저장하지 않는다") {
            // given
            val trip = createTrip()

            // when
            val exception = shouldThrow<BusinessException> { register(trip, trip.memberMemberId, rate = "0.00") }

            // then
            assertSoftly {
                exception.errorCode shouldBe CommonErrorCode.INVALID_REQUEST
                countRecords(trip) shouldBe 0
            }
        }

        test("같은 여행에서 본인이 통화, 일자, 수단과 환율이 모두 같은 기록을 다시 등록하면 409 EXCHANGE_RATE_DUPLICATED로 거절하고 한 건만 남긴다") {
            // given
            val trip = createTrip()
            register(trip, trip.memberMemberId)

            // when
            val exception = shouldThrow<BusinessException> { register(trip, trip.memberMemberId) }

            // then
            assertSoftly {
                exception.errorCode shouldBe ExchangeRateErrorCode.DUPLICATED
                countRecords(trip) shouldBe 1
            }
        }

        test("일자, 환율, 수단이나 통화가 다르거나 다른 참여자가 등록한 기록은 별개 기록으로 저장한다") {
            // given
            val trip = createTrip()
            register(trip, trip.memberMemberId)

            // when
            register(trip, trip.memberMemberId, exchangeDate = exchangeDate.plusDays(1))
            register(trip, trip.memberMemberId, rate = "9.13")
            register(trip, trip.memberMemberId, method = ExchangeMethod.TRAVEL_CARD)
            register(trip, trip.memberMemberId, currency = ExchangeCurrency.USD)
            register(trip, trip.adminMemberId)

            // then
            countRecords(trip) shouldBe 6
        }

        test("같은 기록을 동시에 등록하면 한 건만 저장하고 나머지 요청은 EXCHANGE_RATE_DUPLICATED로 거절한다") {
            // given
            val trip = createTrip()
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
                        runCatching { register(trip, trip.memberMemberId) }.exceptionOrNull()
                    }
                }
            ready.await()
            start.countDown()
            val failures = futures.map { it.get() }
            executor.shutdown()

            // then
            assertSoftly {
                failures.count { it == null } shouldBe 1
                failures.filterNotNull().all { it is BusinessException && it.errorCode == ExchangeRateErrorCode.DUPLICATED } shouldBe true
                countRecords(trip) shouldBe 1
            }
        }

        test("여행이 없으면 404로 거절한다") {
            // given
            val trip = createTrip()

            // when
            val exception = shouldThrow<BusinessException> { register(trip.copy(tripId = Long.MAX_VALUE), trip.memberMemberId) }

            // then
            exception.errorCode shouldBe CommonErrorCode.NOT_FOUND
        }

        test("여행 참여자가 아니거나 여행을 나간 회원이면 403으로 거절하고 저장하지 않는다") {
            // given
            val trip = createTrip()
            val outsiderMemberId = createMember()

            // when
            val outsider = shouldThrow<BusinessException> { register(trip, outsiderMemberId) }
            val left = shouldThrow<BusinessException> { register(trip, trip.leftMemberId) }

            // then
            assertSoftly {
                outsider.errorCode shouldBe CommonErrorCode.FORBIDDEN
                left.errorCode shouldBe CommonErrorCode.FORBIDDEN
                countRecords(trip) shouldBe 0
            }
        }
    }

    private fun register(
        trip: TripFixture,
        memberId: Long,
        currency: ExchangeCurrency = ExchangeCurrency.JPY,
        rate: String = "9.12",
        method: ExchangeMethod = ExchangeMethod.CASH,
        exchangeDate: LocalDate = this.exchangeDate,
    ) = registerManualExchangeRateUseCase.register(memberId, trip.tripId, currency, BigDecimal(rate), method, exchangeDate)

    private fun countRecords(trip: TripFixture): Long =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM manual_exchange_rate WHERE trip_id = ?", Long::class.java, trip.tripId))

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
        addTripMember(tripId, adminMemberId, "민지", "ADMIN", "MEMBER")
        val memberId = addTripMember(tripId, memberMemberId, "지훈", "MEMBER", "MEMBER")
        addTripMember(tripId, leftMemberId, "하준", "MEMBER", "LEFT")
        return TripFixture(tripId, adminMemberId, memberMemberId, memberId, leftMemberId)
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
        val memberMemberId: Long,
        /** [memberMemberId] 회원의 여행 참여자 ID입니다. */
        val memberId: Long,
        val leftMemberId: Long,
    )
}
