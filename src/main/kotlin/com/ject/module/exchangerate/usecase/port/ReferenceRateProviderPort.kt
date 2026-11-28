package com.ject.module.exchangerate.usecase.port

import com.ject.module.exchangerate.domain.PublishedExchangeRate
import java.time.LocalDate

/** 고시 기관에서 외화의 원화 환율을 조회합니다. 저장과 적용일 선택은 하지 않습니다. */
interface ReferenceRateProviderPort {
    /** 환율을 고시하는 기관의 코드입니다. 저장한 행의 `provider`와 같은 값입니다. */
    val provider: String

    /**
     * [currency] 1단위당 원화 환율 중 [date] 이전에 고시된 가장 최근 값을 반환합니다. [date]가 휴일이면 [PublishedExchangeRate.baseDate]는 더 이전입니다.
     * 고시값이 없으면 `null`입니다. 얼마나 오래된 값을 쓸지는 호출자가 판단합니다.
     *
     * 장애, 타임아웃이나 해석할 수 없는 응답이면 503 `EXCHANGE_RATE_PROVIDER_UNAVAILABLE`로 실패합니다. 자동으로 재시도하지 않습니다.
     */
    fun fetch(
        currency: String,
        date: LocalDate,
    ): PublishedExchangeRate?
}
