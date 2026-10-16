package com.ject.global.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.MDC

class RequestIdTaskDecoratorTest :
    FunSpec({
        test("제출한 요청 ID를 전달하고 작업 종료 후 이전 값을 복원한다") {
            try {
                // given
                MDC.put("requestId", "request-123")
                var observedRequestId: String? = null
                val task = RequestIdTaskDecorator().decorate { observedRequestId = MDC.get("requestId") }
                MDC.put("requestId", "worker-previous")

                // when
                task.run()

                // then
                observedRequestId shouldBe "request-123"
                MDC.get("requestId") shouldBe "worker-previous"
            } finally {
                MDC.clear()
            }
        }

        test("요청 ID가 없으면 작업 중 이전 요청 ID를 사용하지 않는다") {
            try {
                // given
                MDC.remove("requestId")
                var observedRequestId: String? = "not-executed"
                val task = RequestIdTaskDecorator().decorate { observedRequestId = MDC.get("requestId") }
                MDC.put("requestId", "worker-previous")

                // when
                task.run()

                // then
                observedRequestId shouldBe null
                MDC.get("requestId") shouldBe "worker-previous"
            } finally {
                MDC.clear()
            }
        }

        test("작업이 실패해도 전달한 요청 ID를 작업 스레드에 남기지 않는다") {
            try {
                // given
                MDC.put("requestId", "request-123")
                val failure = IllegalStateException("task failed")
                val task = RequestIdTaskDecorator().decorate { throw failure }
                MDC.remove("requestId")

                // when
                val thrown = shouldThrow<IllegalStateException> { task.run() }

                // then
                thrown shouldBe failure
                MDC.get("requestId") shouldBe null
            } finally {
                MDC.clear()
            }
        }
    })
