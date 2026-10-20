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

필터는 servlet container와 Spring Security 중 실행 경로를 정합니다. Security 체인 전용 필터가 Spring Bean이면 servlet 자동 등록을 비활성화하고 체인 내 순서를 명시합니다. 로그 기록 기준은 [로깅](logging.md)을 따릅니다.

## 변경 후 검증

- 네 프로필 파일의 키, 계층과 순서가 일치하는지 확인합니다.
- `dev`와 `prod`에 실제 비밀정보나 로컬 접속 기본값이 없는지 확인합니다.
- `test`가 공유 인프라에 연결되지 않는지 확인합니다.
- 필수 환경변수가 없으면 기동이나 해당 구성 초기화가 실패하는지 확인합니다.
- 테스트 DB로 테스트 컨텍스트를 검증하고, 각 배포 환경에서 기동을 확인합니다.
- 인증, 자동 설정 제외, 스케줄러와 필터를 변경하면 해당 동작과 실패 경로를 검증합니다.
