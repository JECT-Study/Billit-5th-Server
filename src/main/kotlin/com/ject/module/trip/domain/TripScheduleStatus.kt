package com.ject.module.trip.domain

/** 여행 목록의 일정 상태이자 필터입니다. 확정 전 여행은 날짜와 관계없이 [INVITING]이고, 확정 후 여행만 날짜로 나눕니다. */
enum class TripScheduleStatus {
    /** 참여자 초대중입니다. 확정 전 여행입니다. */
    INVITING,

    /** 여행 예정입니다. 확정 후 여행이며 오늘이 시작일보다 앞입니다. */
    UPCOMING,

    /** 진행 중입니다. 확정 후 여행이며 오늘이 시작일과 종료일 사이(양 끝 포함)입니다. */
    ONGOING,

    /** 여행 종료입니다. 확정 후 여행이며 오늘이 종료일보다 뒤입니다. */
    ENDED,
}
