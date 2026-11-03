package com.ject.module.inventory.domain

import java.util.UUID

interface StockRepository {
    /** 쓰기 트랜잭션 안에서 호출하며, 반환한 재고의 잠금은 트랜잭션 종료까지 유지합니다. */
    fun findByIdForUpdate(id: UUID): Stock?
}
