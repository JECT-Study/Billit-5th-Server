package com.ject.module.auth.presentation

import com.ject.module.auth.usecase.DeleteExpiredLoginHistoriesUseCase
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 매일 보관 기간이 지난 로그인 기록을 지웁니다. 실행 주기는 `app.auth.login-history.cleanup-cron`에서 정합니다. */
@Component
class LoginHistoryCleanupScheduler(
    private val deleteExpiredLoginHistoriesUseCase: DeleteExpiredLoginHistoriesUseCase,
) {
    @Scheduled(cron = "\${app.auth.login-history.cleanup-cron}", zone = "Asia/Seoul")
    fun deleteExpired() {
        deleteExpiredLoginHistoriesUseCase.delete()
    }
}
