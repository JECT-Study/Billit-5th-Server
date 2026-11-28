package com.ject.module.exchangerate.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

interface ReferenceExchangeRateRepository {
    fun find(
        provider: String,
        currency: String,
        rateDate: LocalDate,
    ): ReferenceExchangeRate?

    /**
     * 같은 [provider], [currency], [rateDate]의 행이 없을 때만 저장하고, 저장했으면 `true`를 반환합니다.
     *
     * 이미 있으면 기존 행을 바꾸지 않고 `false`를 반환합니다. 동시에 저장해도 한 행만 남습니다.
     * 외부 조회를 기다리는 동안 트랜잭션을 열어 두지 않도록 UseCase에 트랜잭션을 두지 않고 이 메서드 하나로 끝냅니다.
     */
    fun saveIfAbsent(
        provider: String,
        currency: String,
        rateDate: LocalDate,
        baseDate: LocalDate,
        rate: BigDecimal,
        createdAt: Instant,
    ): Boolean
}
