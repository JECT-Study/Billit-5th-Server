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
     * 등록자가 여행에 등록한 기록을 최초 등록 시각의 역순, 같으면 ID의 역순으로 [page]번째 페이지(0부터)에서 [size]개 조회합니다.
     *
     * 시작 위치(`page * size`)가 Int 범위를 넘으면 조회할 수 없으므로 [countRecords]로 확인한 뒤 호출합니다.
     */
    fun findRecords(
        tripId: Long,
        createdByTripMemberId: Long,
        page: Int,
        size: Int,
    ): List<ManualExchangeRate>

    /** 등록자가 여행에 등록한 기록의 전체 수입니다. */
    fun countRecords(
        tripId: Long,
        createdByTripMemberId: Long,
    ): Long

    /**
     * 기록 ID, 여행과 등록자가 모두 같은 기록을 지우고 지웠는지 반환합니다.
     *
     * - 조건에 맞는 기록이 없으면 `false`입니다. 기록이 없는 경우와 다른 등록자의 기록인 경우를 구분하지 않습니다.
     * - 트랜잭션: 삭제 한 문장을 단독 트랜잭션으로 실행하므로 호출하는 쪽이 트랜잭션을 열지 않아도 됩니다.
     *   같은 기록을 동시에 삭제하면 한 요청만 `true`를 받습니다.
     */
    fun deleteRecord(
        id: Long,
        tripId: Long,
        createdByTripMemberId: Long,
    ): Boolean
}
