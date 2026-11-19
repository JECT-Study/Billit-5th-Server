package com.ject.module.auth.usecase

import com.ject.module.auth.domain.LoginHistoryRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Period
import java.time.ZoneOffset

/** 보관 기간 3개월이 지난 로그인 기록을 지웁니다. 보관 기간 안의 기록은 남깁니다. */
@Service
class DeleteExpiredLoginHistoriesUseCase(
    private val loginHistoryRepository: LoginHistoryRepository,
    private val clock: Clock,
) {
    @Transactional
    fun delete() {
        val cutoff =
            clock
                .instant()
                .atZone(ZoneOffset.UTC)
                .minus(RETENTION)
                .toInstant()
        val deletedCount = loginHistoryRepository.deleteOccurredBefore(cutoff)
        log.info { "event=login_history_expired_deleted deletedCount=$deletedCount cutoff=$cutoff" }
    }

    companion object {
        val RETENTION: Period = Period.ofMonths(3)
        private val log = KotlinLogging.logger {}
    }
}
