package com.ject.module.trip.presentation.request

import com.ject.module.trip.domain.TripScheduleStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate

/**
 * 내 여행 목록 조회 요청입니다. [today]는 앱 기기의 오늘 날짜이며 진행 상태를 나누는 기준입니다.
 *
 * 빈 [filter]는 생략으로 보지 않고 거절합니다. 변환은 `TripController`의 `@InitBinder`가 맡습니다.
 */
data class ListTripsRequest(
    @field:Min(0)
    val page: Int = 0,
    @field:Min(1)
    @field:Max(100)
    val size: Int = 20,
    val filter: TripScheduleStatus? = null,
    @field:NotNull(message = "필수 값입니다.")
    @field:DateTimeFormat(pattern = DATE_PATTERN)
    val today: LocalDate?,
) {
    companion object {
        /** 엄격한 형식이라 `2026-1-5`나 없는 날짜 `2026-02-30`은 받지 않습니다. */
        private const val DATE_PATTERN = "yyyy-MM-dd"
    }
}
