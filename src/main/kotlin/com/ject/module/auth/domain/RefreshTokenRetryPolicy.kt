package com.ject.module.auth.domain

import java.time.Duration
import java.time.Instant

/**
 * 갱신 응답을 받지 못한 클라이언트가 직전 Refresh Token으로 재시도할 수 있는지 판단합니다.
 *
 * [retryGracePeriod]는 `app.auth.refresh-token.retry-grace-period` 설정값이며 인증 설정이 Bean으로 등록합니다.
 */
class RefreshTokenRetryPolicy(
    private val retryGracePeriod: Duration,
) {
    /** 마지막 회전 시각 [rotatedAt]으로부터 재시도 허용 시간이 지나지 않았으면 `true`입니다. */
    fun isWithinGracePeriod(
        rotatedAt: Instant,
        now: Instant,
    ): Boolean = now.isBefore(rotatedAt.plus(retryGracePeriod))
}
