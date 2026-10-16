package com.ject.global.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ject.external.storage.ObjectStorageException
import com.ject.external.storage.ObjectStorageFailureReason
import com.ject.global.error.GlobalExceptionHandler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory

class ObjectStorageErrorResponseTest :
    FunSpec({
        test("오브젝트 스토리지의 일시적 장애는 내부 정보를 숨기고 503 응답을 반환한다") {
            // given
            val handler = GlobalExceptionHandler()
            val failure =
                ObjectStorageException(
                    reason = ObjectStorageFailureReason.UNAVAILABLE,
                    operation = "head",
                    statusCode = 429,
                    serviceCode = "provider-error",
                    requestId = "private-request-id",
                    cause = IllegalStateException("private-cause-url"),
                )

            val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)

            // when
            val response =
                try {
                    handler.handleObjectStorageException(failure)
                } finally {
                    logger.detachAppender(appender)
                    appender.stop()
                }

            // then
            response.statusCode.value() shouldBe 503
            val body = response.body.shouldNotBeNull()
            body.code shouldBe "COMMON_SERVICE_UNAVAILABLE"
            body.message shouldBe "일시적으로 요청을 처리할 수 없습니다."
            body.errors shouldBe null
            val event = appender.list.single()
            event.throwableProxy shouldBe null
            event.formattedMessage shouldContain "rootCauseType=java.lang.IllegalStateException"
            event.formattedMessage shouldNotContain "private-cause-url"
        }

        for (reason in listOf(
            ObjectStorageFailureReason.CONFIGURATION,
            ObjectStorageFailureReason.NOT_FOUND,
            ObjectStorageFailureReason.UNKNOWN,
        )) {
            test("오브젝트 스토리지 오류($reason)는 업무에서 해석하지 않으면 500 응답을 반환한다") {
                // given
                val handler = GlobalExceptionHandler()
                val failure =
                    ObjectStorageException(
                        reason = reason,
                        operation = "head",
                        statusCode = 404,
                        serviceCode = "provider-error",
                        requestId = "private-request-id",
                        cause = IllegalStateException("private-cause-url"),
                    )

                // when
                val response = handler.handleObjectStorageException(failure)

                // then
                response.statusCode.value() shouldBe 500
                val body = response.body.shouldNotBeNull()
                body.code shouldBe "COMMON_INTERNAL_SERVER_ERROR"
                body.message shouldBe "서버 오류가 발생했습니다."
                body.errors shouldBe null
            }
        }
    })
