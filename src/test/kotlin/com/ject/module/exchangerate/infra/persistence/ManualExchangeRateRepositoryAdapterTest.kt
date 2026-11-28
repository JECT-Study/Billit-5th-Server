package com.ject.module.exchangerate.infra.persistence

import com.ject.global.error.BusinessException
import com.ject.module.exchangerate.domain.ExchangeMethod
import com.ject.module.exchangerate.domain.ExchangeRateErrorCode
import com.ject.module.exchangerate.domain.ManualExchangeRate
import com.ject.module.exchangerate.domain.ManualExchangeRateRepository
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class ManualExchangeRateRepositoryAdapterTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var manualExchangeRateRepository: ManualExchangeRateRepository

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

        test("환율을 소수점 이하 12자리까지 반올림 없이 저장하고 같은 값으로 다시 읽는다") {
            // given
            val (tripId, tripMemberId) = createTripWithMember()

            // when
            val saved = manualExchangeRateRepository.save(record(tripId, tripMemberId, rate = "9.12"))

            // then
            assertSoftly {
                jdbc.queryForObject("SELECT rate::text FROM manual_exchange_rate WHERE id = ?", String::class.java, saved.id) shouldBe
                    "9.120000000000"
                manualExchangeRateRepository
                    .findRecords(tripId, tripMemberId, 0, 20)
                    .single()
                    .rate
                    .compareTo(BigDecimal("9.12")) shouldBe 0
            }
        }

        test("uq_manual_exchange_rate_record 위반은 EXCHANGE_RATE_DUPLICATED로 바꾼다") {
            // given
            val (tripId, tripMemberId) = createTripWithMember()
            manualExchangeRateRepository.save(record(tripId, tripMemberId))

            // when
            val exception = shouldThrow<BusinessException> { manualExchangeRateRepository.save(record(tripId, tripMemberId)) }

            // then
            exception.errorCode shouldBe ExchangeRateErrorCode.DUPLICATED
        }

        test("다른 제약 위반은 원래 실패를 유지한다") {
            // given
            val (_, tripMemberId) = createTripWithMember()

            // when
            val exception =
                shouldThrow<DataIntegrityViolationException> { manualExchangeRateRepository.save(record(Long.MAX_VALUE, tripMemberId)) }

            // then
            exception.message?.contains("fk_manual_exchange_rate_trip_id") shouldBe true
        }
    }

    private fun record(
        tripId: Long,
        tripMemberId: Long,
        rate: String = "9.12",
    ) = ManualExchangeRate(
        tripId = tripId,
        currency = "JPY",
        rate = BigDecimal(rate),
        exchangeDate = LocalDate.parse("2026-11-05"),
        exchangeMethod = ExchangeMethod.CASH,
        createdByTripMemberId = tripMemberId,
    )

    /** 여행 ID와 그 여행에 참여한 회원의 여행 참여자 ID를 만듭니다. */
    private fun createTripWithMember(): Pair<Long, Long> {
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
        val memberId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) VALUES ('GOOGLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(memberId)
        val tripMemberId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, '민지', 'ADMIN'::trip_member_role, 'MEMBER'::trip_member_status, now(), now()) RETURNING id",
                    Long::class.java,
                    tripId,
                    memberId,
                ),
            )
        return tripId to tripMemberId
    }
}
