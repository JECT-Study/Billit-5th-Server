# 환경 설정

## 환경 구분

| 프로필 | 용도 | 외부 자원 |
| --- | --- | --- |
| `local` | 개발자 로컬 실행 | 로컬 PostgreSQL과 Redis를 사용합니다. |
| `test` | 자동화 테스트 | 테스트 전용 DB를 사용합니다. 공유 환경에는 연결하지 않습니다. |
| `dev` | 배포 전 프론트 연동과 QA | `prod`와 분리된 DB, 자격증명과 외부 연동 설정을 사용합니다. |
| `prod` | 실제 서비스 | 운영 자원을 사용하며 필수 설정을 배포 환경에서 주입합니다. |

로컬 프론트가 `dev` 서버를 호출해도 백엔드의 프로필은 `dev`입니다.

## 프로필 설정 파일

환경별 전체 설정은 다음 파일에서 관리합니다.

```text
src/main/resources/
  application-local.yml
  application-dev.yml
  application-prod.yml
src/test/resources/
  application-test.yml
```

- 네 파일의 설정 키, 계층과 배치 순서를 일치시키고 값만 환경별로 변경합니다.
- 키를 추가하거나 제거할 때 네 파일을 함께 수정합니다. 사용하지 않거나 테스트에서 덮어쓰는 값도 키를 유지합니다.
- 각 파일에 전체 설정을 작성합니다. 공통 설정을 `application.yml`로 분리하지 않습니다.
- `spring.config.activate.on-profile`에는 해당 프로필 이름을 명시합니다.
- 환경별로 다른 설정 구조가 필요하면 이유와 적용 범위를 이 문서에 기록합니다.

실행 환경에서는 `SPRING_PROFILES_ACTIVE`로 `local`, `dev`, `prod` 중 하나를 지정합니다. 테스트에서는 `@ActiveProfiles("test")` 등으로 `test`를 지정합니다. 테스트와 배포용 프로필을 함께 활성화하거나, 프로필 미지정을 `prod`로 처리하지 않습니다.

프로필 설정 파일은 `.env`를 자동으로 읽지 않습니다. 실행 시 `--spring.config.import=file:./.env[.properties]` 인자로 실행 디렉터리의 `.env`를 읽도록 지정합니다. `.env`는 properties 형식으로 작성하며 값에 따옴표나 `export`를 사용하지 않습니다.

## 환경별 값

`local`에는 비민감 기본값을 둘 수 있습니다. `dev`와 `prod`의 접속 정보와 자격증명은 환경변수로 받으며 로컬 기본값을 두지 않습니다. 실제 비밀정보는 설정 파일, 문서, 예제와 로그에 기록하지 않습니다.

### DB 설정 예시

```yaml
# application-local.yml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/billit}
```

```yaml
# application-dev.yml, application-prod.yml
# 배포 환경별로 서로 다른 DB_URL을 주입합니다.
spring:
  datasource:
    url: ${DB_URL}
```

DB 통합 테스트는 Testcontainers의 PostgreSQL 컨테이너를 사용합니다. `application-test.yml`의 DB 접속값에는 더미 값을 작성합니다. 테스트 코드의 `@DynamicPropertySource`에서 컨테이너의 접속 정보로 덮어씁니다. 테스트 실행 환경에는 Docker가 필요합니다.

```yaml
# application-test.yml
spring:
  datasource:
    url: jdbc:postgresql://invalid:5432/invalid
    username: invalid
    password: invalid
```

### 애플리케이션과 JPA

다음 값을 모든 환경에 명시합니다.

| 설정 키 | 값 |
| --- | --- |
| `spring.application.name` | `billit` |
| `spring.jpa.hibernate.ddl-auto` | `validate` |
| `spring.jpa.open-in-view` | `false` |

## Redis

네 프로필의 `spring.data.redis`에서 연결을 설정합니다. `local`은 로컬 기본값을 사용하고, `dev`와 `prod`는 Redis 접속 정보를 환경변수로 주입합니다. 두 환경은 서로 다른 Redis 자원을 사용합니다.

| 환경변수 | local 기본값 | dev, prod |
| --- | --- | --- |
| `REDIS_HOST` | `localhost` | 필수 |
| `REDIS_PORT` | `6379` | 필수 |
| `REDIS_USERNAME` | 빈 문자열 | 필수, ACL 사용자가 없으면 빈 문자열 |
| `REDIS_PASSWORD` | 빈 문자열 | 필수, 인증이 없으면 빈 문자열 |
| `REDIS_SSL_ENABLED` | `false` | 필수, 배포 환경의 TLS 설정과 일치시킵니다. |

연결과 명령 timeout은 각각 `2s`로 설정합니다. Redis Repository 자동 탐색은 비활성화하고 Lettuce 연결과 `StringRedisTemplate`은 Spring Boot 자동 설정을 사용합니다.

`test`의 호스트는 `invalid`로 두며 로컬 환경변수를 참조하지 않습니다. 통합 테스트에서는 `IntegrationTestSupport`가 PostgreSQL, Redis와 S3Mock을 함께 실행하고 `@DynamicPropertySource`로 접속 정보를 주입합니다. 단위 테스트는 컨테이너를 실행하지 않습니다.

사용 범위, 로컬 실행과 기능별 결정 사항은 [Redis 가이드](redis.md)를 따릅니다.

## 오브젝트 스토리지

네 프로필의 `app.storage.s3`에서 endpoint, 버킷, 리전과 인증 정보를 설정합니다. Oracle Object Storage의 S3 호환 API를 사용하며 `app.storage.enabled`의 기본값은 `false`입니다. 환경변수, 인증, 임시 접근 URL과 실패 계약은 [오브젝트 스토리지 가이드](storage.md)를 따릅니다.

## 환율 외부 연동

네 프로필의 `app.exchange-rate.frankfurter.source`에서 환율 제공 기관을 설정합니다.

| 설정 | 환경변수 | local, dev, prod 기본값 |
| --- | --- | --- |
| `source` | `EXCHANGE_RATE_SOURCE` | `ECB` |

운영자가 환경변수를 변경하고 재시작하면 환율 제공 기관을 변경할 수 있습니다. Frankfurter가 지원하는 기관을 대문자로 입력하며, 해당 기관의 통화 지원 범위와 날짜별 조회 동작을 확인하고 변경합니다. 사용자가 요청마다 환율 제공 기관을 선택하지 않습니다.

Frankfurter URL은 `https://api.frankfurter.dev`, 연결 타임아웃은 2초, 응답 타임아웃은 5초로 클라이언트 구성에 고정합니다.

`test`는 외부 환경변수를 참조하지 않고 환율 제공 기관을 `ECB`로 고정합니다. 클라이언트 생성 시 외부 요청을 보내지 않습니다. 조회 계약과 실패 처리는 [환율 연동](exchange-rate.md)을 따릅니다.

## API 문서

`app.api-docs.enabled`로 명세와 Scalar의 제공 여부를 제어합니다. 네 프로필에 같은 키를 명시하며 `local`, `dev`는 `true`, `test`, `prod`는 `false`로 설정합니다. 프로필을 지정하지 않으면 기본적으로 비활성화됩니다.

명세는 정적 리소스 자동 공개 경로 밖에 둡니다. [ApiDocsConfig](../../src/main/kotlin/com/ject/global/config/ApiDocsConfig.kt)는 API 문서 활성 환경에서만 Scalar 리소스와 `/api-docs/**`의 YAML 명세를 제공합니다. 이 설정은 문서 접근 인증을 제공하지 않습니다. `dev`의 접근 범위는 배포 환경에서 제한합니다.

경로와 계약 관리 기준은 [API 문서](../contributing/api-documentation.md)를 따릅니다.

## 스키마 변경

모든 프로필에서 Flyway를 활성화하고 `classpath:db/migration`의 SQL을 적용합니다. 스키마 변경과 이력 관리 기준은 [영속성 가이드](../contributing/persistence.md#flyway-migration)를 따릅니다.

## 인증 토큰

네 프로필의 `app.auth.jwt.secret`에서 회원 토큰과 가입 전용 토큰의 HS512 서명 키를 설정합니다.

| 환경변수 | local | test | dev, prod |
| --- | --- | --- | --- |
| `JWT_SECRET` | 개발용 기본값 | 설정 파일의 테스트 전용 값 | 필수, 환경별로 다른 값 |

- 값은 64바이트 이상의 임의 값을 base64로 인코딩해 넣습니다. 예: `openssl rand -base64 64 | tr -d '\n'`
- 값이 없거나 base64가 아니거나 64바이트보다 짧으면 기동이 실패합니다.
- 세션과 Refresh Token 해시는 PostgreSQL `auth_session`에 저장하며 Redis에 두지 않습니다.
- `app.auth.token-encryption.key`(`AUTH_TOKEN_ENCRYPTION_KEY`)는 외부 제공자 토큰(애플 Refresh Token)을 저장할 때 쓰는 AES-256-GCM 키입니다. 32바이트 임의 값을 base64로 인코딩해 넣습니다. 예: `openssl rand -base64 32`. local은 개발용 기본값, test는 설정 파일의 테스트 전용 값, dev와 prod는 기본값 없이 필수이며 환경별로 다른 값을 씁니다. 값이 없거나 32바이트가 아니면 기동이 실패합니다.
- 토큰 암호화 키를 바꾸면 이전 키로 저장한 애플 Refresh Token을 복호화할 수 없습니다. 키를 교체하는 절차는 아직 없으므로 한 번 넣은 값을 유지합니다. 복호화하지 못한 토큰은 `apple_refresh_token_undecryptable` ERROR 로그(저장값과 키 제외)를 남기고, 애플 연결 해제는 애플을 호출하지 않고 행을 지우지 않은 채 "복호화 불가" 결과를 돌려줍니다.
- `app.auth.refresh-token.retry-grace-period`는 네 프로필 모두 `30s`입니다. 회전 후 이 시간 안에 직전 Refresh Token이 다시 오면 응답을 받지 못한 재시도로 보고 한 번만 새 토큰을 발급합니다. 그 밖의 이미 회전한 토큰은 재사용으로 보고 해당 세션을 종료합니다. 30초와 1회는 정책서에 반영됐지만 횟수와 시간은 팀 논의가 남아 있어(Docs `policy/authentication.md`의 `논의 필요`) 설정값으로 둡니다.
- 새 로그인 세션 발급과 토큰 갱신은 `login_history`에 회원 ID, 접속 IP 주소와 시각을 남깁니다. IP 주소는 `server.forward-headers-strategy: native`로 해석한 접속 주소입니다. `app.auth.login-history.cleanup-cron`은 네 프로필 모두 `0 30 3 * * *`(한국 시각 매일 03:30, 운영 DB 백업 04:00 전)이며 3개월이 지난 기록을 지웁니다.

## 소셜 로그인

네 프로필의 `app.auth.social`에서 구글과 카카오 ID 토큰 검증 값을 설정합니다. ID 토큰의 `aud`가 아래 값과 같아야 로그인할 수 있습니다.

| 설정 | 환경변수 | local | test | dev, prod |
| --- | --- | --- | --- | --- |
| `google.client-id` | `GOOGLE_CLIENT_ID` | 가짜 기본값 | 테스트 전용 값 | 필수. dev는 개발용, prod는 출시용 웹 클라이언트 ID |
| `kakao.native-app-key` | `KAKAO_NATIVE_APP_KEY` | 가짜 기본값 | 테스트 전용 값 | 필수, 카카오 네이티브 앱 키 |

- 값이 비어 있으면 기동이 실패합니다. 환경마다 클라이언트 ID를 하나만 받습니다.
- `google.jwk-set-uri`는 `https://www.googleapis.com/oauth2/v3/certs`, `kakao.jwk-set-uri`는 `https://kauth.kakao.com/.well-known/jwks.json`입니다. `test`는 접속할 수 없는 주소를 두고, 테스트에서 자체 서명 키를 제공하는 주소로 덮어씁니다.
- ID 토큰은 RS256 서명, `iss`, `aud`, `exp`를 확인합니다. 허용 `iss`는 구글 `https://accounts.google.com`, `accounts.google.com`, 카카오 `https://kauth.kakao.com`이며 시계 차이는 60초까지 허용합니다.
- JWKS 조회는 연결 2초, 응답 3초로 제한하고 결과를 10분간 캐시합니다. 캐시가 만료되거나 캐시에 없는 키의 토큰이 오면 다시 가져오되, 둘을 합쳐 30초에 두 번까지만 제공자에 요청합니다. 제공자 장애나 이 재조회 제한으로 가져오지 못하면 마지막으로 받은 키 목록을 마지막 조회 성공 후 최대 1시간까지 계속 사용합니다(장애 내성 50분과 캐시 10분). 모르는 키의 토큰이 재조회 한도를 채워도 마지막 키 목록에 있는 키의 토큰은 그 목록으로 검증합니다. 이 값들은 코드에 고정하고, 시각은 주입받은 `Clock`으로 판단합니다.
- 토큰이 유효하지 않으면 401 `AUTH_SOCIAL_AUTHENTICATION_FAILED`로 응답합니다. 마지막 조회가 성공한 키 목록에 없는 키의 토큰은 재조회 제한에 걸려도 401입니다.
- 키를 받은 적이 없거나 마지막 조회가 실패한 상태에서 토큰의 키를 확인하지 못하면 재조회 제한 여부와 관계없이 503 `AUTH_SOCIAL_PROVIDER_UNAVAILABLE`로 응답합니다.

## 애플 로그인

네 프로필의 `app.auth.social.apple`에서 애플 ID 토큰 검증과 인가 코드 교환 값을 설정합니다.

| 설정 | 환경변수 | local | test | dev, prod |
| --- | --- | --- | --- | --- |
| `client-id` | `APPLE_CLIENT_ID` | 가짜 기본값 | 테스트 전용 값 | 필수, 앱 번들 ID. ID 토큰의 `aud`이자 client secret의 `sub` |
| `team-id` | `APPLE_TEAM_ID` | 가짜 기본값 | 테스트 전용 값 | 필수, client secret의 `iss` |
| `key-id` | `APPLE_KEY_ID` | 가짜 기본값 | 테스트 전용 값 | 필수, client secret 헤더의 `kid` |
| `private-key-base64` | `APPLE_PRIVATE_KEY_BASE64` | 빈 값 | 빈 값 | 필수. `.p8` 파일 전체를 base64로 한 줄로 만든 값. 예: `base64 -i AuthKey.p8 \| tr -d '\n'` |
| `private-key-required` | 없음 | `false` | `false` | `true` |
| `apple-endpoint-required` | 없음 | `true` | `false` | `true` |

- `client-id`, `team-id`, `key-id`가 비어 있거나 개인키가 EC P-256 PKCS#8 PEM이 아니면 모든 프로필에서 기동이 실패합니다.
- `private-key-required`가 `true`인 dev, prod는 개인키가 빈 값이어도 기동이 실패합니다. `false`인 local, test는 빈 값을 허용하며, 이때 기동 시 `apple_private_key_missing` WARN 로그를 한 번 남기고 애플 로그인과 연결 해제는 애플을 호출하지 않고 503 `AUTH_SOCIAL_PROVIDER_UNAVAILABLE`로 실패합니다. local에서 애플 로그인을 시험하려면 실제 값을 넣습니다.
- `jwk-set-uri`는 `https://appleid.apple.com/auth/keys`, `token-uri`는 `https://appleid.apple.com/auth/token`, `revoke-uri`는 `https://appleid.apple.com/auth/revoke`입니다.
- 토큰과 폐기 주소에는 인가 코드와 client secret을 보내므로 `apple-endpoint-required`가 `true`이면 두 주소가 `https://appleid.apple.com/`으로 시작하지 않을 때 기동이 실패합니다. 로컬 대역 서버를 쓰는 `test`만 `false`로 두고, 접속할 수 없는 주소를 둔 뒤 테스트에서 대역 서버 주소로 덮어씁니다.
- ID 토큰은 구글, 카카오와 같은 JWKS 캐시와 장애 처리로 검증하고, 허용 `iss`는 `https://appleid.apple.com`입니다.
- client secret은 요청마다 ES256으로 서명하고 5분 뒤 만료됩니다. 토큰 API 호출은 연결 2초, 응답 3초로 제한하며 재시도하지 않습니다.
- 애플이 인가 코드를 거절하면(만료, 재사용, `invalid_grant`, `invalid_request` 등) 401 `AUTH_SOCIAL_AUTHENTICATION_FAILED`로 응답합니다.
- 애플 장애·타임아웃, 요청 한도 초과(429), 서버의 클라이언트 설정 오류(`invalid_client`, `unauthorized_client`, `unsupported_grant_type`)이면 503 `AUTH_SOCIAL_PROVIDER_UNAVAILABLE`로 응답합니다. `invalid_grant` 외의 거절은 ERROR 로그로 남깁니다.
- 교환 응답에 ID 토큰이 없거나 `sub`를 읽을 수 없으면 요청의 ID 토큰과 같은 사용자인지 확인할 수 없어 503 `AUTH_SOCIAL_PROVIDER_UNAVAILABLE`로 응답합니다.
- 교환으로 받은 애플 Refresh Token은 탈퇴 시 연결 해제용으로 `apple_refresh_token`에 암호화해 회원당 하나만 저장합니다.

## 가입 약관

네 프로필의 `app.member.policy.content-base-url`에서 약관 전문을 제공하는 기본 주소를 설정합니다.

| 환경변수 | local | test | dev, prod |
| --- | --- | --- | --- |
| `POLICY_CONTENT_BASE_URL` | `http://localhost:8080/terms` | 설정 파일의 테스트 전용 값 | 필수 |

- 가입 정보 조회의 약관별 `contentUrl`은 `{기본 주소}/{약관 종류 소문자}/{버전}`입니다. 예: `https://example.com/terms/privacy/v1`
- 값이 없거나 `http://`, `https://`로 시작하지 않으면 기동이 실패합니다.
- 가입할 때 동의받는 약관은 이용약관(`TERMS`), 개인정보(`PRIVACY`), 마케팅 수신(`MARKETING`)이며, 종류별로 시행 시각이 지난 버전 중 가장 최근 버전을 사용합니다.

## 인증과 프론트 연동

- 클라이언트가 전달한 `environment`로 서버 프로필이나 운영 자격증명을 선택하지 않습니다.
- `dev`에서 로컬 프론트 연동을 허용할 때 대상 주소와 인증 흐름을 명시합니다. `prod`의 허용 범위는 별도로 관리합니다.
- OAuth 콜백과 로그인 후 프론트 복귀 주소를 구분합니다. 복귀 주소를 선택하게 할 경우 서버의 허용 목록으로 제한합니다.

## 기동 코드와 설정 클래스

[BillitApplication](../../src/main/kotlin/com/ject/BillitApplication.kt)은 `@SpringBootApplication`과 기동 함수만 소유합니다. `@Enable*`, Bean 정의와 자동 설정 제외는 설정 클래스나 프로필 설정 파일에서 관리합니다.

| 설정 범위 | 소유자 |
| --- | --- |
| 애플리케이션 전체의 실행 기능 | 공통 설정 |
| 특정 업무의 실행 정책 | 해당 업무 |
| 외부 시스템 클라이언트 | 해당 외부 연동 |

- 설정 클래스의 패키지는 해당 기능을 구현할 때 정합니다. 빈 설정 클래스를 미리 만들지 않습니다.
- 같은 실행 정책과 변경 이유를 가진 설정을 묶습니다. Bean이나 annotation마다 클래스를 나누지 않습니다.
- 협력 Bean은 `@Bean` 메서드 인자로 주입합니다.
- `@Configuration`의 기본 동작을 변경하면 이유를 기록합니다.

## 비동기 이벤트 처리

[EventAsyncConfig](../../src/main/kotlin/com/ject/global/config/EventAsyncConfig.kt)에서 비동기 실행을 활성화합니다. 이벤트 리스너는 `@Async("eventExecutor")`로 전용 스레드 풀을 지정합니다.

| 설정 | 기본값 | 환경변수 |
| --- | --- | --- |
| `app.event-executor.core-pool-size` | `2` | `EVENT_EXECUTOR_CORE_POOL_SIZE` |
| `app.event-executor.max-pool-size` | `4` | `EVENT_EXECUTOR_MAX_POOL_SIZE` |
| `app.event-executor.queue-capacity` | `100` | `EVENT_EXECUTOR_QUEUE_CAPACITY` |

모든 프로필에 같은 키를 명시합니다. 스레드 수와 작업 큐 크기는 양수이며 최대 스레드 수는 기본 스레드 수 이상이어야 합니다. 값은 처리 지연과 작업 거절 발생을 확인해 조정합니다.

기본 스레드가 사용 중이면 작업을 작업 큐에 넣고, 작업 큐가 가득 차면 최대 스레드 수까지 늘립니다. 최대 스레드와 작업 큐가 모두 사용 중이거나 종료된 스레드 풀에 제출하면 작업을 버리고 `async_event_rejected` 로그를 남깁니다. 요청 스레드에서 대신 실행하거나 자동 재시도하지 않습니다.

[RequestIdTaskDecorator](../../src/main/kotlin/com/ject/global/config/RequestIdTaskDecorator.kt)는 제출 시점의 MDC `requestId`를 작업 스레드로 전달하고, 성공과 실패 모두에서 이전 값을 복원합니다. 트랜잭션과 인증 컨텍스트는 전달하지 않습니다. 이벤트에 필요한 업무 입력은 명시적으로 전달합니다.

## 자동 설정, 스케줄러와 필터

자동 설정은 충돌이나 불필요한 동작을 확인한 경우에만 제외합니다. 제외 항목은 모든 프로필의 `spring.autoconfigure.exclude`에 명시하고, 이유와 제거 조건을 이 문서에 기록합니다.

스케줄러의 전역 활성화와 작업별 실행 주기는 분리합니다. 일반 통합 테스트에서는 예약 작업을 자동 실행하지 않으며, 작업을 직접 호출해 동작을 검증할 수 있도록 구성합니다.

`app.scheduling.enabled`가 `true`일 때만 [SchedulingConfig](../../src/main/kotlin/com/ject/global/config/SchedulingConfig.kt)가 예약 작업을 켭니다. local, dev, prod는 `true`, test는 `false`입니다. 작업별 실행 주기는 각 작업의 설정 키에서 정합니다.

필터는 servlet container와 Spring Security 중 실행 경로를 정합니다. Security 체인 전용 필터가 Spring Bean이면 servlet 자동 등록을 비활성화하고 체인 내 순서를 명시합니다. 로그 기록 기준은 [로깅](logging.md)을 따릅니다.

## 서버와 종료

| 설정 키 | 값 | 적용 조건 |
| --- | --- | --- |
| `server.port` | `${PORT:8080}`, `test`는 `0` | 배포 환경의 컨테이너 포트는 `8080`입니다. |
| `server.shutdown` | `graceful` | 컨테이너 종료 시 처리 중인 요청을 마칩니다. |
| `spring.lifecycle.timeout-per-shutdown-phase` | `20s` | 배포 환경의 컨테이너 종료 대기(30초)보다 짧게 둡니다. |
| `server.forward-headers-strategy` | `native` | 배포 환경에서는 Nginx가 전달한 `X-Forwarded-*` 헤더로 요청 주소를 판단합니다. |
| `spring.datasource.hikari.connection-timeout` | `5000`(밀리초) | DB에 연결할 수 없을 때 요청과 상태 확인의 대기 시간을 제한합니다. |

## 상태 확인과 지표

배포 스크립트, Nginx와 모니터링이 다음 경로를 사용합니다. 경로나 포트를 바꾸려면 인프라 담당과 먼저 맞춥니다.

| 경로 | 포트 | 용도 | 응답 |
| --- | --- | --- | --- |
| `/healthz` | 앱 포트 | 컨테이너 상태 검사 | 프로세스가 동작하면 외부 자원과 관계없이 200 |
| `/readyz` | 앱 포트 | 배포 성공 판단과 외부 접속 확인 | DB와 Redis에 연결되면 200, 아니면 503 |
| `/actuator/health`, `/actuator/prometheus` | 관리 포트 `8081` | 서버 내부 지표 수집 | 관리 포트는 서버 밖으로 열지 않고 Nginx도 `/actuator`를 차단합니다. |

- `management.endpoint.health.probes.enabled`를 켜고 readiness 그룹에 `readinessState,db,redis`, liveness 그룹에 `livenessState`를 포함합니다. 두 그룹은 `additional-path`로 앱 포트의 `/readyz`, `/healthz`에 연결합니다.
- 노출하는 actuator 엔드포인트는 `health`, `prometheus`뿐이며 상태 응답에 상세 정보를 포함하지 않습니다.
- 지표에는 `application` 태그로 `spring.application.name`을 붙입니다.
- `local`, `dev`, `prod`에서는 `management.metrics.distribution.percentiles-histogram.http.server.requests: true`로 HTTP 응답 시간 히스토그램(`http_server_requests_seconds_bucket`)을 노출합니다. Grafana는 이 지표로 응답 시간의 95백분위수(p95)를 계산합니다.
- 관리 포트는 `dev`, `prod`에서 `8081`, `local`에서 `${MANAGEMENT_PORT:8081}`, `test`에서 `0`입니다.
- 인증을 적용해도 `/healthz`, `/readyz`와 관리 포트의 actuator는 인증 없이 응답해야 합니다.

## 배포

[cd 워크플로](../../.github/workflows/cd.yml)가 Billit-5th-Infra의 공통 워크플로로 이미지를 빌드하고 배포합니다.

- `develop`에 PR이 병합되면 병합된 코드로 `lint`, `test`를 다시 실행하고, 통과하면 ARM64 이미지를 `ghcr.io/ject-study/ject-be`에 게시합니다. `docs`와 Markdown만 바뀐 병합은 배포하지 않습니다.
- 개발 서버에는 최신 `develop` 커밋으로 만든 이미지만 배포합니다. 빌드 중 코드가 바뀐 커밋이 병합되면 이전 커밋의 배포는 건너뜁니다.
- `develop` 배포 실행은 한 번에 하나씩 순서대로 진행하고, 대기 중인 실행은 가장 최근 것만 남깁니다.
- 운영 배포와 롤백은 Actions에서 수동으로 실행합니다. 운영에는 개발 배포에 성공한 같은 이미지 digest와 그 커밋만 배포합니다.
- 상태 확인에 실패하면 배포 스크립트가 이전 이미지로 복구를 시도합니다. 이전 이미지가 없거나 복구에도 실패하면 배포 작업이 실패로 끝나며 서버 상태를 직접 확인해야 합니다. DB 스키마는 복구하지 않으므로 마이그레이션은 [영속성 가이드](../contributing/persistence.md#flyway-migration)를 따릅니다.
- 자동 배포는 저장소 변수 `CD_ENABLED`가 `true`일 때만 동작합니다. 환경별 비밀값과 변수는 저장소 Environments `development`, `production`에 둡니다.

## 변경 후 검증

- 네 프로필 파일의 키, 계층과 순서가 일치하는지 확인합니다.
- `dev`와 `prod`에 실제 비밀정보나 로컬 접속 기본값이 없는지 확인합니다.
- `test`가 공유 인프라에 연결되지 않는지 확인합니다.
- 필수 환경변수가 없으면 기동이나 해당 구성 초기화가 실패하는지 확인합니다.
- 테스트 DB로 테스트 컨텍스트를 검증하고, 각 배포 환경에서 기동을 확인합니다.
- 인증, 자동 설정 제외, 스케줄러와 필터를 변경하면 해당 동작과 실패 경로를 검증합니다.
