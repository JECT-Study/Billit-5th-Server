# 로깅

## 로거 사용

로그는 Kotlin Logging으로 작성하고 SLF4J와 Spring Boot 기본 Logback으로 출력합니다. MDC는 SLF4J의 `MDC`를 사용합니다. 운영 코드에서 SLF4J 로거를 직접 선언하지 않습니다.

```kotlin
private val log = KotlinLogging.logger {}
```

메시지는 `log.info { ... }`처럼 람다로 작성하고, 예외는 `log.error(exception) { ... }`으로 전달합니다. 구조화 필드가 필요하면 `atInfo { message = ...; payload = ... }` API를 사용합니다.

## 기록 범위

로그에는 요청 흐름과 장애 원인을 판단하는 데 필요한 정보를 남깁니다. 모든 메서드의 시작과 종료를 기록하지 않습니다.

| 상황 | 기록할 내용 |
| --- | --- |
| 요청 완료 | 요청 ID, HTTP 메서드, 경로, 상태 코드, 처리 시간 |
| 중요한 업무 처리 | 이벤트 이름, 필요한 최소 식별자, 결과 |
| 장애 | 이벤트 이름, 실패 단계, 원인 예외 |

이벤트와 필드 이름은 같은 의미에 같은 이름을 사용합니다. 경로의 쿼리 문자열은 제외하고, 경로에 민감한 값이 있으면 라우트 패턴을 기록하거나 해당 값을 제외합니다.

## 민감정보

다음 값은 로그에 기록하지 않습니다. DEBUG에도 같은 기준을 적용합니다.

- 비밀번호와 비밀번호 해시
- 액세스 토큰, 리프레시 토큰, 쿠키와 인증 헤더
- 요청과 응답 본문 전체, DTO와 Entity 전체
- 이메일과 전화번호 등 개인정보 원문

외부 응답과 예외 메시지에도 민감정보가 포함될 수 있습니다. 객체 전체를 넘기지 않고 필요한 필드를 선택합니다.

### 업무 로그 예시

```kotlin
log.info { "event=account_created accountId=$accountId" }
```

식별자도 추적에 필요한 범위에서만 기록합니다. Kotlin data class의 자동 생성 `toString()`은 민감정보를 제외해 주지 않습니다.

## 로그 레벨

| 레벨 | 기준 |
| --- | --- |
| DEBUG | 개발 또는 장애 진단에 필요한 상세 정보 |
| INFO | 요청 완료와 중요한 정상 처리 결과 |
| WARN | 확인이나 조치가 필요한 이상 상황 |
| ERROR | 예상하지 못한 장애로 작업이 실패한 상황 |

입력 검증 실패, 인증 실패와 정상적인 업무상 거절을 일괄적으로 ERROR로 기록하지 않습니다. 반복적인 공격 징후는 별도로 판단합니다.

## 예외 기록

예외를 최종 처리하는 경계에서 원인과 함께 한 번 기록합니다. 하위 계층에서 기록하고 같은 예외를 다시 던져 중복 기록하지 않습니다.

HTTP 예외 처리기, 보안 필터와 스케줄러는 각자의 실패 경로를 처리합니다. 복구나 재시도를 수행하는 계층은 시도 정보와 최종 장애를 구분합니다.

### 예외 기록 예시

```kotlin
log.error(exception) { "event=operation_failed stage=database_write" }
```

## 요청 추적

[HttpLoggingFilter](../../src/main/kotlin/com/ject/global/logging/HttpLoggingFilter.kt)가 요청 헤더 `X-Request-Id`를 확인합니다. 영문자, 숫자와 `._:-`로 구성된 1~128자 값만 사용하고, 없거나 형식이 맞지 않으면 UUID를 생성합니다. 요청 ID는 MDC의 `requestId`와 응답 헤더 `X-Request-Id`에 기록합니다.

요청 완료 로그는 HTTP 메서드, URI, 상태 코드와 처리 시간을 포함합니다. MVC 라우트 패턴이 있으면 실제 경로 대신 패턴을 기록합니다. 쿼리 문자열과 요청, 응답 본문은 기록하지 않습니다. `/actuator`와 하위 경로는 요청 완료 로그에서 제외합니다.

처리되지 않은 예외로 요청이 종료됐고 응답 상태가 오류로 설정되지 않았다면 완료 로그의 상태는 `500`으로 기록합니다. 예외 스택은 최종 예외 처리 경계에서 기록합니다.

요청 종료 시 `finally`에서 자신이 넣은 값을 제거합니다. 스레드가 재사용되어도 다음 요청에 이전 ID가 남아서는 안 됩니다.

### MDC 정리 예시

```kotlin
MDC.put("requestId", requestId)
try {
    filterChain.doFilter(request, response)
} finally {
    MDC.remove("requestId")
}
```

Spring Boot 기본 Logback을 사용합니다. `local`, `test`는 일반 텍스트로 출력하고 `logging.pattern.level`에 MDC `requestId`를 포함합니다. `dev`, `prod`는 `logging.structured.format.console: logstash`로 콘솔 JSON을 출력합니다. JSON에는 MDC `requestId`와 구조화 필드, 예외 스택이 포함됩니다. 별도 `logback-spring.xml`은 출력 대상이나 형식을 기본 설정으로 표현할 수 없을 때 추가합니다.

이벤트 비동기 작업의 요청 ID 전달과 정리는 [비동기 이벤트 처리](configuration.md#비동기-이벤트-처리)를 따릅니다.

## 검증

- 성공과 실패 요청의 로그를 요청 ID로 연결할 수 있는지 확인합니다.
- 예외 경로에서도 MDC가 정리되고 다음 요청과 섞이지 않는지 확인합니다.
- 대표 요청과 장애 로그에 민감정보가 없는지 확인합니다.
- 같은 장애가 여러 계층에서 중복 기록되지 않는지 확인합니다.
