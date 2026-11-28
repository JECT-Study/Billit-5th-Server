package com.ject.module.exchangerate.infra.persistence.jpa

import com.ject.module.exchangerate.domain.ReferenceExchangeRate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

interface ReferenceExchangeRateJpaRepository : JpaRepository<ReferenceExchangeRate, Long> {
    fun findByProviderAndCurrencyAndRateDate(
        provider: String,
        currency: String,
        rateDate: LocalDate,
    ): ReferenceExchangeRate?

    /** 외부 조회를 사이에 둔 흐름에서 쓰므로 UseCase가 아니라 이 단일 쿼리가 트랜잭션을 엽니다. */
    @Transactional
    @Modifying
    @Query(
        """
        INSERT INTO reference_exchange_rate (provider, currency, rate_date, base_date, rate, created_at)
        VALUES (:provider, :currency, :rateDate, :baseDate, :rate, :createdAt)
        ON CONFLICT (provider, currency, rate_date) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("provider") provider: String,
        @Param("currency") currency: String,
        @Param("rateDate") rateDate: LocalDate,
        @Param("baseDate") baseDate: LocalDate,
        @Param("rate") rate: BigDecimal,
        @Param("createdAt") createdAt: Instant,
    ): Int
}
