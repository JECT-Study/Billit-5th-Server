package com.ject.module.trip.domain

/** 여행의 대표 국가입니다. */
enum class TripCountryCode {
    KR,
    JP,
    US,
    CN,
    EU,

    /** 기타 국가입니다. 국가 코드로 바꾸지 않고 사용자가 입력한 국가명을 함께 저장합니다. */
    ETC,
}
