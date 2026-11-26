package com.ject.module.expense.infra

import com.ject.external.gemini.GeminiClient
import com.ject.external.gemini.GeminiProperties
import com.ject.module.expense.infra.client.GeminiReceiptRecognitionAdapter
import com.ject.module.expense.infra.client.UnavailableReceiptRecognitionAdapter
import com.ject.module.expense.usecase.port.ReceiptRecognitionPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

/** `app.expense.receipt-recognition.provider`로 영수증 인식 엔진을 고릅니다. 쓸 수 없는 설정이면 기동 시 WARN 로그를 한 번 남기고 503으로 응답하는 구현을 씁니다. */
@Configuration
@EnableConfigurationProperties(ReceiptRecognitionProperties::class, GeminiProperties::class)
class ReceiptRecognitionConfig {
    @Bean
    fun receiptRecognitionPort(
        properties: ReceiptRecognitionProperties,
        geminiProperties: GeminiProperties,
        jsonMapper: JsonMapper,
    ): ReceiptRecognitionPort {
        if (properties.provider == ReceiptRecognitionProperties.Provider.GEMINI && geminiProperties.apiKey.isNotBlank()) {
            return GeminiReceiptRecognitionAdapter(GeminiClient(geminiProperties), jsonMapper)
        }
        log.warn { "event=receipt_recognition_unavailable provider=${properties.provider} impact=recognition_returns_503" }
        return UnavailableReceiptRecognitionAdapter()
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
