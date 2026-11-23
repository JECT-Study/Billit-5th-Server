package com.ject.module.auth.implement

import com.ject.module.auth.domain.LoginHistory
import com.ject.module.auth.domain.LoginHistoryRepository
import com.ject.module.auth.domain.LoginHistoryType
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant

/**
 * 현재 HTTP 요청의 접속 IP 주소로 로그인 기록을 남깁니다.
 *
 * IP 주소는 `server.forward-headers-strategy: native`로 Tomcat이 Nginx의 `X-Forwarded-For`를 해석한 `remoteAddr`입니다.
 * 내부 프록시 주소를 오른쪽부터 건너뛰고 처음 만난 외부 주소를 쓰므로, 클라이언트가 헤더 앞부분에 넣은 값으로 바뀌지 않습니다.
 * 로그인과 토큰 갱신의 여러 진입점마다 IP 주소를 인자로 넘기지 않도록 요청 범위에서 읽습니다. HTTP 요청 밖에서 호출하면 IP 주소 없이 저장합니다.
 * 저장은 호출한 쪽의 트랜잭션에 참여하고, 트랜잭션이 없으면 자체 트랜잭션으로 커밋합니다.
 */
@Component
class LoginHistoryCreator(
    private val loginHistoryRepository: LoginHistoryRepository,
) {
    fun create(
        memberId: Long,
        loginType: LoginHistoryType,
        occurredAt: Instant,
    ) {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        loginHistoryRepository.save(
            LoginHistory(memberId = memberId, loginType = loginType, ipAddress = request?.remoteAddr, occurredAt = occurredAt),
        )
    }
}
