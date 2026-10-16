package com.ject.external.exchangerate.frankfurter

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.exchange-rate.frankfurter")
class FrankfurterProperties(
    val source: String,
) {
    init {
        require(source.matches(Regex("[A-Z][A-Z0-9_]*"))) { "환율 제공 기관은 대문자로 입력해야 합니다." }
    }
}
