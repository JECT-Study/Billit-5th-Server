package com.ject.module.trip.infra

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.trip.invitation")
class TripInvitationProperties(
    /** 초대 링크의 기본 주소입니다. 여행별 초대 링크는 이 주소 뒤에 초대 코드를 붙여 만듭니다. */
    val baseUrl: String,
) {
    init {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "초대 링크 기본 주소는 http 또는 https 주소여야 합니다."
        }
    }
}
