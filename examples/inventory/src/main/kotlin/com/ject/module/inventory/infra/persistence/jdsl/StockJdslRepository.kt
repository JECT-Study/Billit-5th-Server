package com.ject.module.inventory.infra.persistence.jdsl

import com.ject.module.inventory.domain.Stock
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.hibernate.extension.createQuery
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class StockJdslRepository(
    private val entityManager: EntityManager,
    private val jpqlRenderContext: JpqlRenderContext,
) {
    fun findByIdForUpdate(id: UUID): Stock? {
        val query =
            jpql {
                select(entity(Stock::class))
                    .from(entity(Stock::class))
                    .where(path(Stock::id).equal(id))
            }

        return entityManager
            .createQuery(query, jpqlRenderContext)
            .apply { lockMode = LockModeType.PESSIMISTIC_WRITE }
            .singleResultOrNull
    }
}
