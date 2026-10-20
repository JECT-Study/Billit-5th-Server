# API 문서

## 계약 원본

요청과 응답의 공통 형식은 [API 공통 규칙](api-conventions.md)을 따릅니다.

`src/main/resources/api-docs`의 YAML 파일을 API 계약의 원본으로 직접 관리합니다. 루트 명세가 도메인별 명세와 공통 정의를 `$ref`로 연결합니다. Controller annotation으로 명세를 생성하지 않습니다.

| 파일 | 책임 |
| --- | --- |
| [openapi.yml](../../src/main/resources/api-docs/openapi.yml) | OpenAPI 버전, 서버, 태그와 경로별 참조, 공통 component 참조 |
| [domains/inventory.yml](../../src/main/resources/api-docs/domains/inventory.yml) | 도메인의 `paths`와 전용 `components` |
| [common/components.yml](../../src/main/resources/api-docs/common/components.yml) | 공통 스키마, 응답, 파라미터와 보안 정의 |

새 도메인은 `domains/<업무>.yml`에 작성하고 루트의 `paths`에서 해당 Path Item을 참조합니다. 경로를 참조하는 JSON Pointer에서는 `/`를 `~1`로, `~`를 `~0`으로 이스케이프합니다. 공통 또는 다른 파일의 정의는 해당 YAML 기준 상대 경로와 JSON Pointer로 참조합니다.

```yaml
paths:
  /api/v1/learning/stocks/{stockId}/reservations:
    $ref: './domains/inventory.yml#/paths/~1api~1v1~1learning~1stocks~1{stockId}~1reservations'
```

도메인 전용 스키마는 소유 도메인 파일에 정의합니다. 공통으로 사용하는 정의만 `common/components.yml`에 둡니다. 묶인 명세에서 component 이름과 `operationId`가 중복되지 않도록 작성합니다. 생성한 `build/generated/api-docs/openapi.json`은 직접 수정하거나 커밋하지 않습니다.

- 확정되지 않은 API, 인증 방식과 오류 응답을 미리 정의하지 않습니다. 공통 스키마와 응답은 `components`에서 관리하고, 개별 API는 `paths`에 정의합니다.
- `operationId`는 전체 명세에서 고유하게 작성하고 Controller 클래스나 메서드 이름 변경만으로 바꾸지 않습니다.
- 필수값, null 허용 여부, 검증 조건, 상태 코드와 응답 구조를 실제 HTTP 동작과 일치시킵니다.
- 반복되는 스키마와 응답은 `components`에 정의하고 `$ref`로 참조합니다.
- 예제에 실제 개인정보, 토큰이나 자격증명을 넣지 않습니다.

## 명세와 구현 동기화

- Controller, 요청과 응답 DTO, 입력 검증이나 인증 처리 등에서 HTTP API 계약을 변경하면 해당 도메인 YAML을 같은 변경에서 갱신합니다. 경로, HTTP 메서드, 파라미터, 헤더, 요청과 응답 스키마, 상태 코드와 인증 요구사항을 포함합니다.
- 도메인 YAML의 계약을 먼저 변경하면 기존 Controller와 관련 구현, HTTP 테스트도 같은 변경에서 해당 계약에 맞춥니다.
- Controller가 없는 API는 확정된 계약을 해당 도메인 YAML에 먼저 작성할 수 있습니다. 이후 Controller, 요청과 응답 DTO, 검증과 관련 코드를 구성할 때 해당 명세를 기준으로 구현하고 HTTP 테스트로 계약 일치 여부를 확인합니다.
- 클래스 분리나 이름 변경처럼 HTTP API 계약에 영향이 없는 내부 변경은 명세 수정 대상에서 제외합니다.

## 분류

`tags`는 해당 프로젝트의 도메인 업무 단위로 관리합니다. 같은 업무의 Controller를 나누어도 같은 태그를 사용합니다. API가 많아 탐색하기 어려워지면 응집된 기능 단위로 태그를 나눕니다.

Controller 분리만으로 경로, `operationId`나 태그를 변경하지 않습니다. Controller 책임과 분리 기준은 [아키텍처](architecture.md#업무-내부-구조)를 따릅니다.

## Scalar

기본 포트의 로컬 주소입니다.

| 대상 | 주소 |
| --- | --- |
| Scalar | <http://localhost:8080/swagger-ui.html> |

[build.gradle.kts](../../build.gradle.kts)의 `bundleOpenApi`가 Swagger Parser로 외부 `$ref`를 내부 참조로 해석하고 명세를 검증합니다. `processResources`는 이 작업에 의존하며, 묶인 JSON 명세를 Scalar HTML에 포함합니다. 명세 변경 후에는 `processResources` 또는 `bootRun`으로 HTML을 다시 생성합니다. `/openapi.yml` 경로는 제공하지 않습니다. [scalar-initializer.js](../../src/main/resources/api-docs/scalar/scalar-initializer.js)는 페이지에 포함된 명세를 읽습니다. `openapi.yml`의 `servers: [{ url: / }]`는 같은 서버로 API 요청을 보냅니다. 환경별 주소를 JavaScript에 하드코딩하지 않습니다.

Scalar는 [WebJar](../../build.gradle.kts)의 standalone 번들을 서버에 포함해 제공하고 [ApiDocsConfig](../../src/main/kotlin/com/ject/global/config/ApiDocsConfig.kt)가 UI 리소스를 매핑합니다. WebJar 버전을 바꾸면 설정 클래스의 리소스 경로도 함께 변경합니다. 기존 문서 주소인 `/swagger-ui.html`에서 Scalar를 제공합니다.

인증 정보는 브라우저 저장소에 유지하지 않습니다. 외부 CDN, 기본 웹 폰트와 요청 프록시를 사용하지 않으며 UI의 API 실행은 실제 서버에 직접 요청을 보냅니다.

환경별 활성화는 [환경 설정](../operations/configuration.md#api-문서)을 따릅니다.

## 변경 후 확인

- `./gradlew bundleOpenApi`로 OpenAPI 3.0.3 문법과 `$ref`, `operationId` 중복을 검증합니다. `check`와 `processResources`에서도 실행합니다.
- 활성 환경에서 Scalar가 페이지에 포함된 명세의 경로와 스키마를 표시하는지 확인합니다.
- `/openapi.yml`이 제공되지 않고 비활성 환경에서 `/swagger-ui.html`도 제공되지 않는지 확인합니다.
- API 구현 시 주요 성공과 실패 응답을 HTTP 테스트로 검증하고 명세와 대조합니다.
