package com.ject.module.auth.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.auth.refresh-token")
class RefreshTokenProperties(
    /** 갱신 응답을 받지 못한 클라이언트가 직전 Refresh Token으로 한 번 재시도할 수 있는 회전 후 시간입니다. */
    val retryGracePeriod: Duration,
) {
    init {
        require(!retryGracePeriod.isNegative) { "Refresh Token 재시도 허용 시간은 음수일 수 없습니다." }
    }
}
