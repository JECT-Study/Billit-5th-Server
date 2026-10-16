package com.ject.global.config

import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator

class RequestIdTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val requestId = MDC.get("requestId")
        return Runnable {
            val previousRequestId = MDC.get("requestId")
            try {
                if (requestId == null) {
                    MDC.remove("requestId")
                } else {
                    MDC.put("requestId", requestId)
                }
                runnable.run()
            } finally {
                if (previousRequestId == null) {
                    MDC.remove("requestId")
                } else {
                    MDC.put("requestId", previousRequestId)
                }
            }
        }
    }
}
