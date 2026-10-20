# API 공통 규칙

## 적용 범위

HTTP API의 공통 규칙입니다. 계약 원본은 [루트 명세](../../src/main/resources/api-docs/openapi.yml)와 도메인별 YAML이며, 명세 관리와 구현 동기화는 [API 문서](api-documentation.md)를 따릅니다.

## 기본 경로와 전송 형식

| 항목 | 기준 |
| --- | --- |
| 기본 경로 | `/api/v1` |
| JSON Content-Type | `application/json; charset=UTF-8` |
| 파일 업로드 | `multipart/form-data` |
| 파일 다운로드 | 파일의 미디어 타입 |
| 본문 없는 응답 | `204 No Content` |

## HTTP 메서드와 경로

| 항목 | 규칙 |
| --- | --- |
| 리소스 | 복수형 명사, 소문자, kebab-case: `/trips`, `/exchange-rates` |
| 계층 | 경로로 표현: `/trips/{tripId}/expenses/{expenseId}` |
| 행위 | 동사 금지. 상태 변경 행위는 예외: `POST /settlements/{settlementId}/complete` |
| 조회 | `GET` |
| 생성과 행위 | `POST` |
| 일부 수정 | `PATCH` |
| 전체 교체 | `PUT` |
| 삭제 | `DELETE` |

## 필드 이름과 타입

| 대상 | 규칙 |
| --- | --- |
| JSON 필드 | camelCase: `tripId`, `createdAt` |
| boolean 이름 | `is`/`has` 접두어: `isAdmin`, `hasReceipt` |
| boolean 값 | JSON boolean: `true`, `false` |
| enum | 대문자 SNAKE_CASE 상수 이름: `"IN_PROGRESS"` |
| ID | DB 타입과 관계없이 JSON 문자열 |
| 날짜 | `YYYY-MM-DD` 문자열: `"2026-10-17"` |
| 시각 | ISO 8601 UTC 문자열: `"2026-10-17T03:00:00Z"` |

- API enum: `toString()` 재정의와 별도 JSON 값 매핑 금지. 상수 이름 변경은 API 계약 변경으로 처리
- 시각 표시: FE에서 기기 시간대로 변환. 서버 타입과 저장은 [시간 처리](../operations/time.md) 참고
- 외부 공개 식별자와 초대 링크: 추측하기 어려운 값 사용, 순번 ID 노출 금지

## 필수 입력과 선택 입력

| 상황 | 규칙 |
| --- | --- |
| 필수 입력 | 생략과 `null` 모두 거절 |
| 선택 입력 | 생략 가능, `null` 허용 여부는 필드별 명시 |
| 문자열 | 빈 문자열과 공백을 값 없음으로 자동 처리 금지 |
| PATCH 필드 생략 | 기존 값 유지 |
| PATCH의 `null` | 삭제 가능한 필드만 값 제거, 나머지는 거절 |
| 응답의 단일 값 없음 | 필드 생략 없이 `null` |
| 응답의 빈 목록 | `[]` |

조건부 응답 필드는 포함 조건을 명세에 적습니다. `page`는 페이지네이션 응답에만 포함합니다.

## 금액과 환율

| 항목 | 기준 |
| --- | --- |
| 금액, 환율, 환산액, 정산액 | 소수점 이하 2자리 문자열 |
| 표시 문자 | 통화 기호, 천 단위 쉼표 금지 |
| 계산 | 업무 코드: `BigDecimal`, 반올림 `HALF_UP`. FE: 결과 표시 |
| 통화 | ISO 4217 코드 함께 전달 |
| 원화 환율 | 외화 1단위당 원화 금액 |
| 환산 순서 | 환율 2자리 반올림 → 원금에 곱하기 → 결과 2자리 반올림 |
| 업무 규칙 | 음수와 0 허용 여부, 최대 금액, 환율 유효성, 정산 배분 규칙은 업무 코드에서 검증하고 API별 명시 |

```json
{
  "amount": "3500.00",
  "currency": "JPY",
  "exchangeRate": "9.12",
  "amountKrw": "31920.00"
}
```

## 성공 응답

```json
{
  "data": {
    "tripId": "123",
    "name": "도쿄 여행"
  }
}
```

| 상황 | 상태 코드 | 응답 |
| --- | --- | --- |
| 조회와 수정 성공 | `200 OK` | `data`: 처리 결과 |
| 생성 성공 | `201 Created` | `data`: 생성 결과. 식별 가능한 리소스는 `Location` 헤더 제공 |
| 본문 없는 성공 | `204 No Content` | 본문 없음 |
| 비동기 처리 접수 | `202 Accepted` | `data`: 처리 상태나 조회 방법. 완료로 표현 금지 |

- 공통 `timestamp`, `code`, `message`, `result` 제외
- 파일 다운로드: JSON으로 감싸지 않고 파일 본문 반환

## 실패 응답

```json
{
  "code": "USER_EMAIL_ALREADY_REGISTERED",
  "message": "이미 가입된 이메일입니다.",
  "errors": null
}
```

```json
{
  "code": "COMMON_INVALID_REQUEST",
  "message": "요청이 올바르지 않습니다.",
  "errors": [
    {
      "field": "amount",
      "reason": "0보다 커야 합니다."
    }
  ]
}
```

| 필드 | 기준 |
| --- | --- |
| `code` | FE 분기용. 의미가 드러나는 `도메인_사유`, 대문자 SNAKE_CASE |
| `message` | 기본 사용자 표시 문구 |
| `errors` | 필드 검증 오류의 `field`, `reason` 목록. `field` 이름순 정렬 |
| 필드 오류 없음 또는 특정 불가 | `errors: null` |

- BE: 코드와 메시지 함께 관리, 변경 시 FE에 공유
- `message`, `reason`: 사용자에게 표시 가능한 문장. 내부 예외, SQL, 민감정보 노출 금지

### HTTP 오류 상태

| 코드 | 의미 |
| --- | --- |
| `400` | 요청 형식 또는 입력값 검증 실패 |
| `401` | 인증 정보 누락, 만료 또는 유효하지 않은 인증 정보 |
| `403` | 접근 또는 변경 권한 없음 |
| `404` | 요청한 리소스 없음 |
| `405` | 지원하지 않는 HTTP 메서드 |
| `406` | 지원하지 않는 응답 형식 |
| `409` | 중복이나 현재 업무 상태와의 충돌 |
| `413` | 업로드 크기 제한 초과 |
| `415` | 지원하지 않는 요청 미디어 타입 |
| `500` | 예상하지 못한 서버 오류 |
| `503` | 일시적인 서비스 이용 불가 |

오류별 코드, 메시지와 상태 코드의 대응은 OpenAPI 명세에 정의합니다.

## 입력 검증

| 대상 | 처리 |
| --- | --- |
| 길이, 범위, 허용 값 | API별 명시 |
| 공백 | 자동 제거 금지. 이메일 등 정규화 대상만 규칙 명시 |
| JSON 타입 변환 | Jackson 기본 동작 허용. 변환 실패는 `400` |
| enum | 이름 문자열만 허용. 숫자 입력과 기본값 대체 금지 |
| 정의하지 않은 JSON 필드 | `400` |
| 필수값 누락, 타입과 범위 위반 | 입력 검증 오류 |
| 중복, 권한, 업무 상태에 따른 거절 | 업무 오류 |

## 인증

| 항목 | 기준 |
| --- | --- |
| 인증 헤더 | `Authorization: Bearer {accessToken}` |
| 로그인 | Access 토큰 불필요 |
| 인증 요구와 공개 API 예외 | OpenAPI `security`에 명시 |
| 인증 실패 코드와 메시지 | 오류별 명세에서 관리 |

## 목록 조회와 페이지네이션

Offset 기반 페이지네이션을 사용합니다.

```http
GET /api/v1/trips/123/expenses?page=0&size=20&sort=paidAt,desc
```

```json
{
  "data": [],
  "page": {
    "page": 0,
    "size": 20,
    "totalElements": 0,
    "hasNext": false
  }
}
```

| 항목 | 규칙 |
| --- | --- |
| `page` | 0부터 시작, 기본 0 |
| `size` | 기본 20, 최소 1, 최대 100 |
| `sort` | `필드,방향`. 지원 필드와 기본 정렬은 API별 명시 |
| `totalElements` | 전체 필터 조건 일치 건수 |
| `hasNext` | 다음 페이지 존재 여부 |
| 필터 조합 | 기본 AND, OR 조건은 API별 명시 |
| 필터 생략 | 해당 조건 미적용 |
| 정렬 동률 | 마지막 정렬 기준에 ID 추가 |
| 잘못된 페이지 조건 | 음수 페이지, 크기 범위 위반, 미지원 정렬: `400` |
| 마지막 페이지 초과 | `200`, `data: []`. `totalElements`는 전체 조건 일치 건수 유지 |

## 중복 요청과 동시 수정

| 상황 | 규칙 |
| --- | --- |
| 생성과 상태 변경 요청 중 | FE 버튼 비활성화 |
| 중복 생성 | 서버의 DB 제약 등으로 보호, 충돌 시 `409` |
| 응답 유실 후 재전송 | 업무상 중복 생성과 구분 |
| 멱등성 키 사용 API | 키 범위, 유효 기간, 동일 키의 다른 요청 처리, 재응답 규칙 명시 |
| 동시 수정 | API별 충돌 방지 방식과 실패 응답 명시 |
| 삭제된 대상 재삭제 | API별 성공 또는 `404` 명시 |
| 자동 재시도 | 중복 실행해도 안전한 요청만 허용 |

## 파일 업로드와 다운로드

| 항목 | 규칙 |
| --- | --- |
| 업로드 응답 | `data`에 파일 ID 또는 접근 정보 |
| 업로드 제한 | API별 허용 형식, 파일별 최대 크기와 개수 명시 |
| 파일 검증 | 확장자와 요청 `Content-Type` 외에 실제 내용 검증 |
| 다운로드 | 파일 본문과 해당 `Content-Type` 반환 |
| 첨부 파일명 | `Content-Disposition` |
| 비공개 파일 | 업로드와 다운로드 각각 접근 권한 확인 |

## 요청 추적

- 생성, 검증, 응답 헤더와 로그: [로깅의 요청 추적](../operations/logging.md#요청-추적) 참고
- 공통 응답 본문: 요청 ID 제외
- 브라우저에서 헤더 접근 시: CORS 노출 헤더에 `X-Request-Id` 추가

## 공통 코드 사용

| 대상 | 사용법 |
| --- | --- |
| 성공 | Controller에서 [ApiResponse](../../src/main/kotlin/com/ject/global/response/ApiResponse.kt)로 명시적으로 감싸기 |
| 상태 코드와 헤더 | `ResponseEntity`로 지정. 본문 없는 성공은 `ResponseEntity.noContent().build<Void>()` |
| 페이지 요청 | API별 요청 DTO에서 페이지, 필터와 정렬 조건 조합. 공통 페이지 범위와 API별 허용 정렬 검증 |
| 페이지 응답 | [PageResponse](../../src/main/kotlin/com/ject/global/pagination/PageResponse.kt) 사용 |
| 업무 오류 | 업무별 enum에서 [ErrorCode](../../src/main/kotlin/com/ject/global/error/ErrorCode.kt) 구현. [BusinessException](../../src/main/kotlin/com/ject/global/error/BusinessException.kt)으로 전달 |
| 공통 오류 | [CommonErrorCode](../../src/main/kotlin/com/ject/global/error/CommonErrorCode.kt)의 코드, 상태와 메시지 사용 |
| 예외 변환 | [GlobalExceptionHandler](../../src/main/kotlin/com/ject/global/error/GlobalExceptionHandler.kt)에서 처리. DB 예외의 업무 의미는 해당 업무에서 판별 |
| 금액과 환율 요청 DTO | `String`으로 받고 Bean Validation으로 소수점 이하 2자리 형식 검증. Controller에서 `BigDecimal`로 변환 |
| 금액과 환율 응답 DTO | 업무에서 확정한 `BigDecimal`을 2자리 문자열로 표현. `setScale(2, UNNECESSARY).toPlainString()` |
| ID DTO | `String`으로 선언. 전체 `Long`의 직렬화 방식 변경 금지 |
| JSON 설정 | 환경별 YAML의 `spring.jackson`에서 미정의 필드 거절, enum 숫자 입력 금지와 이름 기반 변환 설정. 나머지는 Jackson 기본 동작 |

DTO는 입력 형식 검증과 출력 표현 변환만 담당합니다. 금액 범위, 환율 유효성, 계산과 반올림은 해당 업무의 UseCase와 Policy에서 처리합니다. 응답 DTO에서 반올림하거나 업무 규칙을 검증하지 않습니다.
