package com.ject.module.expense.presentation.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate

/** 지출 목록 조회 요청입니다. [fromDate]와 [toDate]는 결제 날짜의 시작과 끝(양 끝 포함)이며, 생략하면 그 경계를 두지 않습니다. */
data class ListExpensesRequest(
    @field:Min(0)
    val page: Int = 0,
    @field:Min(1)
    @field:Max(100)
    val size: Int = 20,
    @field:DateTimeFormat(pattern = DATE_PATTERN)
    val fromDate: LocalDate? = null,
    @field:DateTimeFormat(pattern = DATE_PATTERN)
    val toDate: LocalDate? = null,
) {
    companion object {
        /** 엄격한 형식이라 `2026-1-5`나 없는 날짜 `2026-02-30`은 받지 않습니다. */
        private const val DATE_PATTERN = "yyyy-MM-dd"
    }
}
