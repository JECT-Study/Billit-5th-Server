# 환율 연동

## 조회 계약

[ExchangeRateClient](../../src/main/kotlin/com/ject/external/exchangerate/ExchangeRateClient.kt)의 `fetch(base, quotes, date)`로 단일 날짜를 조회합니다. Frankfurter 구현은 `/v2/providers/{source}/rates`에 기준 통화, 대상 통화 목록과 날짜를 전달합니다.

- 기준 통화와 대상 통화는 ISO 4217 대문자 코드입니다. 대상 통화 목록은 비어 있을 수 없습니다.
- `date`는 주입받은 `Clock`의 `Asia/Seoul` 기준 오늘 이하여야 합니다. 잘못된 입력은 외부 호출 전에 `IllegalArgumentException`으로 거절합니다.
- `rate`는 기준 통화 1단위당 대상 통화의 양입니다. 같은 통화의 환율은 1입니다.
- 결과의 `date`는 실제 데이터 기준일입니다. 휴일이나 발표 전에는 요청일보다 이전일 수 있으며 통화별로 다를 수 있습니다.
- 결과는 날짜, 대상 통화 오름차순입니다. 정상적인 빈 목록과 일부 통화 누락은 그대로 반환합니다.
- `source`는 설정된 환율 제공 기관입니다. 초기값은 ECB입니다. 설정 방법은 [환경 설정](configuration.md#환율-외부-연동)을 따릅니다.

## 정밀도와 업무 경계

외부 응답의 환율은 `BigDecimal`로 받고 추가 반올림하지 않습니다. 선택한 기준 통화로 직접 조회하며 EUR 원본을 이용한 별도 교차 계산은 수행하지 않습니다.

원화 환산과 반올림은 업무에서 [API 공통 규칙](../contributing/api-conventions.md#금액과-환율)을 적용합니다. 누락된 통화, 서로 다른 기준일과 오래된 데이터의 사용 여부도 업무에서 판단합니다. 외부 연동은 저장과 적용일 선택을 수행하지 않습니다.

## 실패 계약

외부 조회 실패는 `ExchangeRateException`으로 전달합니다. 원인 예외와 HTTP 상태 코드를 보존하며 자동 재시도하지 않습니다.

| reason | 의미 |
| --- | --- |
| `CONNECTION` | 연결 실패 등 네트워크 오류 |
| `TIMEOUT` | 연결 또는 응답 시간 초과 |
| `HTTP_ERROR` | 외부 API의 HTTP 오류. 404도 빈 목록으로 바꾸지 않습니다. |
| `INVALID_RESPONSE` | 본문 없음, 파싱 실패, 필수값 누락, 양수가 아닌 환율, 요청과 다른 통화, 요청일 이후 데이터, 중복 대상 통화 또는 같은 통화의 환율이 1이 아닌 응답 |

외부 API 계약은 [Frankfurter 공식 문서](https://frankfurter.dev/)와 [v2 OpenAPI](https://api.frankfurter.dev/v2/openapi.json)를 참고합니다.
