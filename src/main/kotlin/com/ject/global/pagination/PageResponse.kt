package com.ject.global.pagination

data class PageResponse<T>(
    val data: List<T>,
    val page: PageInfo,
) {
    companion object {
        fun <T> from(
            data: List<T>,
            page: Int,
            size: Int,
            totalElements: Long,
            hasNext: Boolean,
        ): PageResponse<T> =
            PageResponse(
                data = data.toList(),
                page =
                    PageInfo(
                        page = page,
                        size = size,
                        totalElements = totalElements,
                        hasNext = hasNext,
                    ),
            )
    }
}
