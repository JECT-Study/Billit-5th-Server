# 시간 처리

## 시점과 날짜

생성, 수정, 만료처럼 하나의 시점을 나타내는 값은 `Instant`를 사용합니다. 날짜 자체가 업무 값이면 `LocalDate`를 사용합니다.

`LocalDateTime`에는 시간대가 없으므로 하나의 시점을 확정할 수 없습니다. 생성, 수정, 만료 시각에는 사용하지 않습니다.

## 현재 시각

현재 시각은 주입받은 `Clock`에서 구합니다. 공통 `Clock`은 [TimeConfig](../../src/main/kotlin/com/ject/global/time/TimeConfig.kt)에서 `Clock.systemUTC()`로 제공하고, 한 작업의 기준 시각은 한 번 구해 전달합니다.

### 한 작업의 기준 시각

```kotlin
val now = clock.instant()
val createdAt = now
val expiresAt = now.plus(ttl)
```

`ttl`은 해당 업무 정책에서 정합니다. 테스트에서는 같은 코드에 고정된 `Clock`을 전달합니다.

## 저장과 API

| 대상 | 기준 |
| --- | --- |
| JPA 시점 필드 | `Instant`로 선언합니다. |
| PostgreSQL 컬럼 | migration에서 `TIMESTAMPTZ`로 정의합니다. |
| DB 세션과 JDBC 시간대 | UTC로 맞춘다. |
| API 응답 | `2026-10-13T00:00:00Z`와 같은 ISO 8601 UTC 형식으로 전달합니다. |
| offset이 있는 API 입력 | offset을 반영해 같은 시점의 Instant로 변환합니다. |

`TIMESTAMPTZ`는 원래 입력의 시간대 이름을 보존하지 않습니다. 시간대 자체가 업무 데이터이면 별도로 보관합니다.

## 업무 시간대와 기간 경계

업무상 날짜를 계산할 때 시간대를 명시합니다. JVM의 기본 시간대를 변경하거나 기본값에 의존하지 않습니다.

### 한국 날짜 계산 예시

```kotlin
val today = clock.instant()
    .atZone(ZoneId.of("Asia/Seoul"))
    .toLocalDate()
```

다음 조건은 해당 업무 정책에서 정합니다.

- “오늘”과 “자정”의 기준 시간대
- “하루 뒤”가 24시간 경과인지 다음 달력 날짜인지
- 기간의 시작과 끝을 포함하는지
- 만료 시각과 정확히 같은 순간에 유효한지

시간대나 offset 없이 받은 지역 시각은 API 계약에 따라 해석합니다. 계약이 없으면 임의로 시간대를 붙이지 않습니다.

## 검증

현재 시각이 결과에 영향을 주는 테스트는 `Clock.fixed()`로 고정합니다.

```kotlin
val clock = Clock.fixed(
    Instant.parse("2026-10-13T00:00:00Z"),
    ZoneOffset.UTC,
)
```

- 정책의 경계 직전, 정확한 경계와 직후를 확인합니다.
- 실제 PostgreSQL에 저장하고 다시 읽어 같은 시점인지 확인합니다. 나노초와 DB 저장 정밀도의 차이를 반영합니다.
- API 응답의 UTC 형식과 입력 offset의 변환 결과를 확인합니다.
