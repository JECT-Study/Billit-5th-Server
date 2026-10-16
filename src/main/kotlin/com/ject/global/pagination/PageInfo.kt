package com.ject.global.pagination

data class PageInfo(
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val hasNext: Boolean,
)
