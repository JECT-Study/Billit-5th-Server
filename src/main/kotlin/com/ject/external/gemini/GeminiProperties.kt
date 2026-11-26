package com.ject.external.gemini

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.gemini")
class GeminiProperties(
    /** Gemini API 키입니다. 비어 있으면 Gemini를 호출하지 않습니다. */
    val apiKey: String,
    /** `models/{model}:generateContent`에 넣을 모델 코드입니다. */
    val model: String,
    val baseUrl: String,
    val connectTimeout: Duration,
    val readTimeout: Duration,
) {
    init {
        require(model.isNotBlank()) { "Gemini 모델 코드가 비어 있습니다." }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "Gemini 기본 주소는 http 또는 https 주소여야 합니다." }
        require(!connectTimeout.isNegative && !connectTimeout.isZero && !readTimeout.isNegative && !readTimeout.isZero) {
            "Gemini 타임아웃은 0보다 커야 합니다."
        }
    }
}
