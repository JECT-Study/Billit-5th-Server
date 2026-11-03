package com.ject.module.inventory.infra.persistence.jdsl

import com.ject.module.inventory.domain.Reservation
import com.ject.module.inventory.domain.ReservationResult
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.hibernate.extension.createQuery
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReservationJdslRepository(
    private val entityManager: EntityManager,
    private val jpqlRenderContext: JpqlRenderContext,
) {
    fun findRecentByStockId(stockId: UUID): List<ReservationResult> {
        val query =
            jpql {
                selectNew<ReservationResult>(
                    path(Reservation::id),
                    path(Reservation::stockId),
                    path(Reservation::quantity),
                    path(Reservation::reservedAt),
                ).from(entity(Reservation::class))
                    .where(path(Reservation::stockId).equal(stockId))
                    .orderBy(path(Reservation::reservedAt).desc(), path(Reservation::id).desc())
            }

        // JPQL은 JDSL로 작성하고, limit은 JPA 실행 옵션으로 지정합니다.
        return entityManager
            .createQuery(query, jpqlRenderContext)
            .apply { maxResults = 20 }
            .resultList
    }
}
