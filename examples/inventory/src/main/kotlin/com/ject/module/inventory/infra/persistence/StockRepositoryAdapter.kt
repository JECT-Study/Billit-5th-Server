package com.ject.module.inventory.infra.persistence

import com.ject.module.inventory.domain.Stock
import com.ject.module.inventory.domain.StockRepository
import com.ject.module.inventory.infra.persistence.jdsl.StockJdslRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class StockRepositoryAdapter(
    private val stockJdslRepository: StockJdslRepository,
) : StockRepository {
    override fun findByIdForUpdate(id: UUID): Stock? = stockJdslRepository.findByIdForUpdate(id)
}
