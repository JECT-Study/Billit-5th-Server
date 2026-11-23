package com.ject.module.trip.domain

/** 여행 확정 상태입니다. 여행 기간에 따른 예정, 진행 중, 종료 표시와 구분합니다. */
enum class TripStatus {
    /** 확정 전입니다. 참여자를 초대하는 중이며 생성한 여행의 처음 상태입니다. */
    UNCONFIRMED,

    /** 확정 후입니다. 확정 전으로 되돌릴 수 없습니다. */
    CONFIRMED,
}
