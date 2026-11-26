package com.ject.module.expense.infra.persistence.jdsl

import com.ject.module.expense.domain.Expense
import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.hibernate.extension.createQuery
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class ExpenseJdslRepository(
    private val entityManager: EntityManager,
    private val jpqlRenderContext: JpqlRenderContext,
) {
    fun findExpenses(
        tripId: Long,
        fromDate: LocalDate?,
        toDate: LocalDate?,
        offset: Int,
        limit: Int,
    ): List<Expense> {
        val query =
            jpql {
                select(entity(Expense::class))
                    .from(entity(Expense::class))
                    .where(expenseCondition(tripId, fromDate, toDate))
                    .orderBy(path(Expense::paidDate).desc(), path(Expense::id).desc())
            }

        return entityManager
            .createQuery(query, jpqlRenderContext)
            .apply {
                firstResult = offset
                maxResults = limit
            }.resultList
    }

    fun countExpenses(
        tripId: Long,
        fromDate: LocalDate?,
        toDate: LocalDate?,
    ): Long {
        val query =
            jpql {
                select(count(path(Expense::id)))
                    .from(entity(Expense::class))
                    .where(expenseCondition(tripId, fromDate, toDate))
            }

        return entityManager.createQuery(query, jpqlRenderContext).singleResult
    }

    /** 여행의 삭제되지 않은 지출 중 결제 날짜가 [fromDate]부터 [toDate]까지(양 끝 포함)인 지출입니다. 날짜가 `null`이면 그 경계를 두지 않습니다. */
    private fun Jpql.expenseCondition(
        tripId: Long,
        fromDate: LocalDate?,
        toDate: LocalDate?,
    ): Predicate =
        and(
            path(Expense::tripId).equal(tripId),
            path(Expense::deletedAt).isNull(),
            fromDate?.let { path(Expense::paidDate).greaterThanOrEqualTo(it) },
            toDate?.let { path(Expense::paidDate).lessThanOrEqualTo(it) },
        )
}
