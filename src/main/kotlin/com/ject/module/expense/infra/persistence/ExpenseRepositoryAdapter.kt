package com.ject.module.expense.infra.persistence

import com.ject.global.persistence.violatedUniqueConstraint
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.Expense
import com.ject.module.expense.domain.ExpenseCalculation
import com.ject.module.expense.domain.ExpenseIdempotencyKeyDuplicatedException
import com.ject.module.expense.domain.ExpenseItem
import com.ject.module.expense.domain.ExpenseItemMember
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.domain.ExpenseShareAmount
import com.ject.module.expense.infra.persistence.jdsl.ExpenseJdslRepository
import com.ject.module.expense.infra.persistence.jpa.ExpenseItemJpaRepository
import com.ject.module.expense.infra.persistence.jpa.ExpenseItemMemberJpaRepository
import com.ject.module.expense.infra.persistence.jpa.ExpenseJpaRepository
import com.ject.module.expense.infra.persistence.jpa.ExpenseShareAmountJpaRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class ExpenseRepositoryAdapter(
    private val expenseJpaRepository: ExpenseJpaRepository,
    private val expenseItemJpaRepository: ExpenseItemJpaRepository,
    private val expenseItemMemberJpaRepository: ExpenseItemMemberJpaRepository,
    private val expenseShareAmountJpaRepository: ExpenseShareAmountJpaRepository,
    private val expenseJdslRepository: ExpenseJdslRepository,
) : ExpenseRepository {
    override fun saveExpense(expense: Expense): Long =
        try {
            checkNotNull(expenseJpaRepository.saveAndFlush(expense).id)
        } catch (exception: DataIntegrityViolationException) {
            if (exception.violatedUniqueConstraint() != IDEMPOTENCY_KEY_UNIQUE_CONSTRAINT) {
                throw exception
            }
            throw ExpenseIdempotencyKeyDuplicatedException(exception)
        }

    override fun saveItem(item: ExpenseItem): Long = checkNotNull(expenseItemJpaRepository.saveAndFlush(item).id)

    override fun saveItemMembers(itemMembers: List<ExpenseItemMember>) {
        expenseItemMemberJpaRepository.saveAllAndFlush(itemMembers)
    }

    override fun saveShareAmounts(shareAmounts: List<ExpenseShareAmount>) {
        expenseShareAmountJpaRepository.saveAllAndFlush(shareAmounts)
    }

    override fun findExpenseByIdempotencyKey(idempotencyKey: UUID): Expense? = expenseJpaRepository.findByIdempotencyKey(idempotencyKey)

    override fun findExpense(expenseId: Long): Expense? = expenseJpaRepository.findByIdOrNull(expenseId)

    override fun existsActiveExpense(tripId: Long): Boolean = expenseJpaRepository.existsByTripIdAndDeletedAtIsNull(tripId)

    override fun findItems(expenseId: Long): List<ExpenseItem> = expenseItemJpaRepository.findByExpenseIdOrderBySortOrder(expenseId)

    override fun findItemMembers(expenseId: Long): List<ExpenseItemMember> = expenseItemMemberJpaRepository.findByExpenseId(expenseId)

    override fun findShareAmounts(expenseId: Long): List<ExpenseShareAmount> =
        expenseShareAmountJpaRepository.findByExpenseIdOrderByTripMemberId(expenseId)

    override fun findExpenses(
        tripId: Long,
        fromDate: LocalDate?,
        toDate: LocalDate?,
        offset: Int,
        limit: Int,
    ): List<Expense> = expenseJdslRepository.findExpenses(tripId, fromDate, toDate, offset, limit)

    override fun countExpenses(
        tripId: Long,
        fromDate: LocalDate?,
        toDate: LocalDate?,
    ): Long = expenseJdslRepository.countExpenses(tripId, fromDate, toDate)

    override fun acquireEditLock(
        expenseId: Long,
        tripId: Long,
        ownerTripMemberId: Long,
        token: UUID,
        expiresAt: Instant,
        now: Instant,
    ): Boolean = expenseJpaRepository.acquireEditLock(expenseId, tripId, ownerTripMemberId, token, expiresAt, now) == 1

    override fun extendEditLock(
        expenseId: Long,
        tripId: Long,
        ownerTripMemberId: Long,
        token: UUID,
        extension: Duration,
        now: Instant,
    ): Instant? =
        // 갱신이 영속성 컨텍스트를 비웠으므로 이어지는 조회는 같은 트랜잭션에서 방금 바꾼 DB 값을 읽습니다.
        expenseJpaRepository
            .extendEditLock(expenseId, tripId, ownerTripMemberId, token, extension.seconds, now)
            .takeIf { it == 1 }
            ?.let { expenseJpaRepository.findByIdOrNull(expenseId)?.editLockExpiresAt }

    override fun releaseEditLock(
        expenseId: Long,
        tripId: Long,
        ownerTripMemberId: Long,
        token: UUID,
    ): Boolean = expenseJpaRepository.releaseEditLock(expenseId, tripId, ownerTripMemberId, token) == 1

    override fun updateExpenseIfEditLocked(
        expenseId: Long,
        tripId: Long,
        ownerTripMemberId: Long,
        token: UUID,
        command: CreateExpenseCommand,
        calculation: ExpenseCalculation,
        now: Instant,
    ): Boolean =
        expenseJpaRepository.updateIfEditLocked(
            expenseId = expenseId,
            tripId = tripId,
            ownerTripMemberId = ownerTripMemberId,
            token = token,
            name = command.name,
            splitType = command.splitType,
            originalAmount = calculation.originalAmount,
            discountAmount = calculation.discountAmount,
            amount = calculation.amount,
            currency = command.currency,
            paidDate = command.paidDate,
            payerTripMemberId = command.payerTripMemberId,
            paymentMethod = command.paymentMethod,
            exchangeRate = command.exchangeRate,
            exchangeRateSource = command.exchangeRateSource,
            krwAmount = calculation.krwAmount,
            now = now,
        ) == 1

    override fun deleteItemsAndShareAmounts(expenseId: Long) {
        // 품목별 부담자 연결이 품목을 참조하므로 연결을 먼저 지웁니다.
        expenseItemMemberJpaRepository.deleteAllByExpenseId(expenseId)
        expenseItemJpaRepository.deleteAllByExpenseId(expenseId)
        expenseShareAmountJpaRepository.deleteAllByExpenseId(expenseId)
    }

    companion object {
        private const val IDEMPOTENCY_KEY_UNIQUE_CONSTRAINT = "uq_expense_idempotency_key"
    }
}
