package com.ject.module.member.infra

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.member.policy")
class PolicyProperties(
    /** 약관 전문을 제공하는 기본 주소입니다. 약관별 주소는 이 주소 뒤에 약관 종류와 버전을 붙여 만듭니다. */
    val contentBaseUrl: String,
) {
    init {
        require(contentBaseUrl.startsWith("https://") || contentBaseUrl.startsWith("http://")) {
            "약관 전문 기본 주소는 http 또는 https 주소여야 합니다."
        }
    }
}
