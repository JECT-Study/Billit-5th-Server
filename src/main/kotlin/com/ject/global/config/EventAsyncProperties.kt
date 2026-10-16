package com.ject.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.event-executor")
data class EventAsyncProperties(
    val corePoolSize: Int,
    val maxPoolSize: Int,
    val queueCapacity: Int,
) {
    init {
        require(corePoolSize > 0) { "이벤트 처리 스레드 풀의 기본 스레드 수는 0보다 커야 합니다." }
        require(maxPoolSize >= corePoolSize) { "이벤트 처리 스레드 풀의 최대 스레드 수는 기본 스레드 수 이상이어야 합니다." }
        require(queueCapacity > 0) { "이벤트 처리 스레드 풀의 작업 큐 용량은 0보다 커야 합니다." }
    }
}
