package com.ject.module.exchangerate.infra.persistence

import com.ject.module.exchangerate.domain.ReferenceExchangeRate
import com.ject.module.exchangerate.domain.ReferenceExchangeRateRepository
import com.ject.module.exchangerate.infra.persistence.jpa.ReferenceExchangeRateJpaRepository
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Repository
class ReferenceExchangeRateRepositoryAdapter(
    private val referenceExchangeRateJpaRepository: ReferenceExchangeRateJpaRepository,
) : ReferenceExchangeRateRepository {
    override fun find(
        provider: String,
        currency: String,
        rateDate: LocalDate,
    ): ReferenceExchangeRate? = referenceExchangeRateJpaRepository.findByProviderAndCurrencyAndRateDate(provider, currency, rateDate)

    override fun saveIfAbsent(
        provider: String,
        currency: String,
        rateDate: LocalDate,
        baseDate: LocalDate,
        rate: BigDecimal,
        createdAt: Instant,
    ): Boolean = referenceExchangeRateJpaRepository.insertIfAbsent(provider, currency, rateDate, baseDate, rate, createdAt) == 1
}
