package com.ject.module.exchangerate.domain

interface ManualExchangeRateRepository {
    /**
     * 개인 환율을 저장하고 저장한 기록을 반환합니다.
     *
     * - 같은 여행에서 같은 등록자가 같은 통화, 환전 날짜, 환전 수단과 환율의 기록을 이미 저장했으면 `uq_manual_exchange_rate_record` 위반으로 판단해
     *   [com.ject.global.error.BusinessException]으로 `EXCHANGE_RATE_DUPLICATED`를 던집니다. 다른 DB 오류는 그대로 던집니다.
     * - 트랜잭션: 저장하고 바로 flush하므로 호출하는 쪽이 트랜잭션을 열지 않아도 됩니다.
     */
    fun save(manualExchangeRate: ManualExchangeRate): ManualExchangeRate

    /**
     * 등록자가 여행에 등록한 기록을 [currency]로 걸러(null이면 모든 통화) 최초 등록 시각의 역순, 같으면 ID의 역순으로 [page]번째 페이지(0부터)에서 [size]개 조회합니다.
     *
     * 시작 위치(`page * size`)가 Int 범위를 넘으면 조회할 수 없으므로 [countRecords]로 확인한 뒤 호출합니다.
     */
    fun findRecords(
        tripId: Long,
        createdByTripMemberId: Long,
        currency: ExchangeCurrency?,
        page: Int,
        size: Int,
    ): List<ManualExchangeRate>

    /** 등록자가 여행에 등록한 기록 중 [currency](null이면 모든 통화)에 해당하는 기록의 전체 수입니다. */
    fun countRecords(
        tripId: Long,
        createdByTripMemberId: Long,
        currency: ExchangeCurrency?,
    ): Long
}
