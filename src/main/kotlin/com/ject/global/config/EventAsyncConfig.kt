package com.ject.global.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
@EnableConfigurationProperties(EventAsyncProperties::class)
class EventAsyncConfig : AsyncConfigurer {
    @Bean
    fun eventExecutor(properties: EventAsyncProperties): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = properties.corePoolSize
        executor.maxPoolSize = properties.maxPoolSize
        executor.queueCapacity = properties.queueCapacity
        executor.setThreadNamePrefix("event-")
        executor.setTaskDecorator(RequestIdTaskDecorator())
        executor.setRejectedExecutionHandler { _, pool ->
            log.error {
                "event=async_event_rejected activeCount=${pool.activeCount} " +
                    "poolSize=${pool.poolSize} queueSize=${pool.queue.size} shutdown=${pool.isShutdown}"
            }
        }
        return executor
    }

    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler =
        AsyncUncaughtExceptionHandler { exception, method, _ ->
            log.error(exception) {
                "event=async_event_failed listener=${method.declaringClass.simpleName} method=${method.name}"
            }
        }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
