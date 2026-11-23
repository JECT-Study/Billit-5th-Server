package com.ject.global.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** 예약 작업을 켭니다. 통합 테스트는 `app.scheduling.enabled: false`로 끄고 작업을 직접 호출해 검증합니다. */
@Configuration
@EnableScheduling
@ConditionalOnBooleanProperty("app.scheduling.enabled")
class SchedulingConfig
