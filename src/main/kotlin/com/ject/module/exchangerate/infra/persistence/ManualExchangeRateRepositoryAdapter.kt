package com.ject.module.exchangerate.infra.persistence

import com.ject.global.error.BusinessException
import com.ject.global.persistence.violatedUniqueConstraint
import com.ject.module.exchangerate.domain.ExchangeRateErrorCode
import com.ject.module.exchangerate.domain.ManualExchangeRate
import com.ject.module.exchangerate.domain.ManualExchangeRateRepository
import com.ject.module.exchangerate.infra.persistence.jpa.ManualExchangeRateJpaRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository

@Repository
class ManualExchangeRateRepositoryAdapter(
    private val manualExchangeRateJpaRepository: ManualExchangeRateJpaRepository,
) : ManualExchangeRateRepository {
    override fun save(manualExchangeRate: ManualExchangeRate): ManualExchangeRate =
        try {
            manualExchangeRateJpaRepository.saveAndFlush(manualExchangeRate)
        } catch (exception: DataIntegrityViolationException) {
            if (exception.violatedUniqueConstraint() != RECORD_UNIQUE_CONSTRAINT) {
                throw exception
            }
            throw BusinessException(ExchangeRateErrorCode.DUPLICATED, exception)
        }

    override fun findRecords(
        tripId: Long,
        createdByTripMemberId: Long,
        page: Int,
        size: Int,
    ): List<ManualExchangeRate> =
        manualExchangeRateJpaRepository.findByTripIdAndCreatedByTripMemberId(
            tripId,
            createdByTripMemberId,
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
        )

    override fun countRecords(
        tripId: Long,
        createdByTripMemberId: Long,
    ): Long = manualExchangeRateJpaRepository.countByTripIdAndCreatedByTripMemberId(tripId, createdByTripMemberId)

    override fun deleteRecord(
        id: Long,
        tripId: Long,
        createdByTripMemberId: Long,
    ): Boolean = manualExchangeRateJpaRepository.deleteRecord(id, tripId, createdByTripMemberId) > 0

    companion object {
        private const val RECORD_UNIQUE_CONSTRAINT = "uq_manual_exchange_rate_record"
    }
}
