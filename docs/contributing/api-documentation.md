# API 문서

## 계약 원본

요청과 응답의 공통 형식은 [API 공통 규칙](api-conventions.md)을 따릅니다.

`src/main/resources/api-docs`의 YAML 파일을 API 계약의 원본으로 직접 관리합니다. 루트 명세가 도메인별 명세와 공통 정의를 `$ref`로 연결합니다. Controller annotation으로 명세를 생성하지 않습니다.

| 파일 | 책임 |
| --- | --- |
| [openapi.yml](../../src/main/resources/api-docs/openapi.yml) | OpenAPI 버전, 서버, 태그와 경로별 참조, 공통 component 참조 |
| `domains/<업무>.yml` | 도메인의 `paths`와 전용 `components` |
| [common/components.yml](../../src/main/resources/api-docs/common/components.yml) | 공통 스키마, 응답, 파라미터와 보안 정의 |

새 도메인은 `domains/<업무>.yml`에 작성하고 루트의 `paths`에서 해당 Path Item을 참조합니다. 경로를 참조하는 JSON Pointer에서는 `/`를 `~1`로, `~`를 `~0`으로 이스케이프합니다. 공통 또는 다른 파일의 정의는 해당 YAML 기준 상대 경로와 JSON Pointer로 참조합니다.

아래는 [재고 예제 명세](../../examples/inventory/api-docs/inventory.yml)를 실제 API에 적용할 때의 참조 예시입니다.

```yaml
paths:
  '/api/v1/learning/stocks/{stockId}/reservations':
    $ref: './domains/inventory.yml#/paths/~1api~1v1~1learning~1stocks~1{stockId}~1reservations'
```

도메인 전용 스키마는 소유 도메인 파일에 정의합니다. 공통으로 사용하는 정의만 `common/components.yml`에 둡니다. 전체 명세에서 component 이름과 `operationId`가 중복되지 않도록 작성합니다.

- 확정되지 않은 API, 인증 방식과 오류 응답을 미리 정의하지 않습니다. 공통 스키마와 응답은 `components`에서 관리하고, 개별 API는 `paths`에 정의합니다.
- `operationId`는 전체 명세에서 고유하게 작성하고 Controller 클래스나 메서드 이름 변경만으로 바꾸지 않습니다.
- 필수값, null 허용 여부, 검증 조건, 상태 코드와 응답 구조를 실제 HTTP 동작과 일치시킵니다.
- 반복되는 스키마와 응답은 `components`에 정의하고 `$ref`로 참조합니다.
- 예제에 실제 개인정보, 토큰이나 자격증명을 넣지 않습니다.

## 작성 순서와 형식

신규 API와 수정하는 API에 아래 규칙을 적용합니다. API 동작과 응답 공통 정책은 [API 공통 규칙](api-conventions.md)에서 관리하고, 이 문서는 해당 계약을 명세에 작성하는 방법을 정합니다.

공통 작성 형식은 유지하되, 전송 형식과 조건에 따라 아래 절을 적용합니다. 모든 API를 JSON 단건 조회 형태로 맞추지 않습니다.

| 유형 | 추가로 작성할 내용 |
| --- | --- |
| 목록 조회 | 페이지 구조, 필터, 정렬과 빈 목록 예제 |
| 생성 | 생성 결과, 상태 코드와 `Location` 헤더 |
| 일부 수정 | 필드별 생략, `null`과 빈 값의 의미 |
| 본문 없는 성공 | `204` 응답, `content`와 본문 예제 제외 |
| 배열 또는 객체 Query | [직렬화 방식과 실제 Query 문자열](#배열과-객체-query) |
| 파일 전송 | [multipart 요청](#파일-업로드), [파일 응답](#파일-다운로드) |
| 조건별로 다른 구조 | [분기별 스키마와 예제](#조건부-요청과-응답) |

이 문서의 유형별 YAML은 작성 방법을 보여 주는 가상 예시입니다. 예시의 경로, 필드, 허용 값과 제한을 실제 업무 정책으로 적용하지 않습니다. 스트리밍, 웹훅처럼 별도 전송 계약이 필요한 API는 이 형식만으로 충분하다고 간주하지 않고 이벤트 구조, 전달 조건과 실패 처리 등 해당 계약을 별도로 정의합니다.

1. 기존 계약과 업무 정책에서 요청 권한, 입력, 성공 결과와 실패 조건을 확인합니다.
2. Path Item과 operation을 작성하고 요청 및 응답 스키마를 연결합니다.
3. 입력 제약과 필드 설명을 작성합니다.
4. 전송 형식에 맞는 요청 예제, 성공 응답과 오류 코드별 예제를 작성합니다.
5. Scalar 표시와 계약 일치 여부를 확인합니다.

### YAML 형식

- 들여쓰기는 공백 2칸을 사용합니다. 탭을 사용하지 않습니다. 시퀀스의 `-`는 기존 명세처럼 소유 키와 같은 깊이에 둡니다.
- 경로, HTTP 상태 코드와 `$ref` 값은 따옴표로 감쌉니다. 문자열 ID와 금액 예제도 따옴표로 감쌉니다.
- 여러 문단, 목록과 표가 있는 `description`은 `|`를 사용합니다. 한 문장은 한 줄에 작성하며 자동 줄바꿈으로 나누지 않습니다.
- HTTP 상태 코드는 성공 응답부터 숫자 오름차순으로 배치합니다.
- `required`와 `properties`는 같은 필드 순서로 작성합니다. 예제의 필드 순서도 스키마와 맞춥니다.
- 선택 항목에 내용이 없으면 키 자체를 생략합니다. 빈 `description`, `parameters: []`나 빈 제목을 넣지 않습니다. 공개 API의 `security: []`는 예외입니다.
- `null` 예제는 빈 YAML 값 대신 `null`로 명시합니다.

| 위치 | 키 순서 |
| --- | --- |
| 도메인 파일 | `paths`, `components` |
| Path Item | 공통 `parameters`, HTTP 메서드별 operation |
| operation | `tags`, `operationId`, `summary`, `description`, `security`, `parameters`, `requestBody`, `responses` |
| parameter | `name`, `in`, `required`, `description`, `style`, `explode`, `schema`, `example` 또는 `examples` |
| requestBody | `required`, `description`, `content` |
| response | `description`, `headers`, `content` |
| media type | `schema`, `examples`, `encoding` |
| 객체 스키마 | `type`, `description`, `required`, `additionalProperties`, `properties` |
| 분기 스키마 | `description`, `oneOf` 또는 `anyOf`, `discriminator` |
| 단일 값 스키마 | `type`, `format`, `nullable`, 제약, `description`, `default`, `example` |
| Example Object | `summary`, `description`, `value` |

Path와 operation 양쪽에 같은 파라미터를 중복 정의하지 않습니다. 같은 경로의 모든 메서드에서 의미가 같으면 Path Item에, 특정 메서드에만 필요하면 operation에 둡니다. 경로 자체의 업무별 배치 순서는 기존 파일을 유지합니다.

## API 이름과 설명

### 태그와 operationId

- `tags`는 [분류](#분류)의 업무 태그를 사용합니다.
- `operationId`는 lowerCamelCase의 영문 동사와 대상을 조합합니다. 조회는 `get`, 목록 조회는 `list`, 생성은 `create`, 수정은 `update`, 삭제는 `delete`를 사용합니다. 참여와 확정 등 별도 업무 행위는 `join`, `confirm`처럼 해당 행위를 사용합니다.
- 예시는 `getTripInvitation`, `listTrips`, `createTrip`입니다. 기존 식별자는 표현 통일만을 위해 변경하지 않습니다.
- `operationId`에 버전, HTTP 메서드명, Controller 이름이나 `Api` 접미사를 붙이지 않습니다.
- 업무 모델의 스키마 이름은 `Invitation`처럼 UpperCamelCase를 사용합니다. 요청과 응답 전용 모델은 `<대상><동작>Request`, `<대상><동작>Response`로 구별합니다. 공통 component와 이름이 겹치면 업무명을 붙입니다. 기존 이름은 표현 통일만을 위해 변경하지 않습니다.

### 인증

- 인증이 필요한 operation에는 `security: [{ bearerAuth: [] }]`를 명시합니다. 전역 인증 설정에만 의존하지 않습니다.
- 공개 operation에는 `security: []`를 명시합니다. 공개 API에 인증 실패 응답을 기계적으로 추가하지 않습니다.
- Bearer 토큰은 보안 스키마로 표현하고, 역할과 소유 관계는 `description`의 `요청 권한`에 적습니다.

### summary

- 한국어 `대상 + 동작`의 명사형으로 작성합니다. 끝에 마침표를 붙이지 않습니다.
- `초대 정보 조회`, `여행 생성`, `여행 참여 확정`처럼 기능을 구별할 수 있게 작성합니다.
- 원본 스펙의 `AUTH-01` 같은 API 코드는 제목에 넣지 않고 기능 이름만 적습니다.
- URL, HTTP 메서드, 인증 여부, 오류 조건과 `API`라는 단어는 넣지 않습니다.

### description

첫 문장은 무엇을 조회하거나 변경하는지 `합니다`체로 작성합니다. 이어지는 절은 아래 순서와 제목을 사용하고, 해당 내용이 없는 절은 생략합니다.

| 절 | 작성할 내용 |
| --- | --- |
| `### 요청 권한` | 로그인 외의 역할, 소유 관계와 접근 범위 |
| `### 처리 조건` | 요청이 허용되는 업무 상태, 선행 조건과 상태 변경 결과 |
| `### 사용 시 주의` | 재호출, 외부 부수 효과와 호출자가 오해할 수 있는 동작 |

- `요청 권한`에는 `관리자만 가능` 대신 `해당 여행의 관리자만 요청할 수 있습니다`처럼 대상과 관계를 적습니다. 인증 방식은 `security`로 표현합니다.
- `처리 조건`에는 `확정 전 여행에서만 조회할 수 있습니다`처럼 허용 조건을 적습니다. 실패 상태와 오류 코드는 `responses`에서 관리합니다.
- 상태 변경 API에는 변경 전후 상태와 함께 변경되는 데이터를 적습니다. 트랜잭션 구현, 테이블명과 내부 클래스명은 적지 않습니다.
- 재요청 동작, 멱등성, 동시 수정과 외부 연동은 확정된 계약이 있을 때만 적습니다. 타임아웃, 재시도나 오류 우선순위를 임의로 약속하지 않습니다.
- 필드 목록과 전체 JSON을 본문에 반복하지 않습니다. 입력과 출력은 파라미터, 스키마와 예제로 설명합니다.
- 확정되지 않은 정책, 구현 계획과 `TODO`를 계약 설명에 넣지 않습니다. 구현 전 API도 확정된 계약만 작성하며, 명세가 있다는 이유로 구현 완료로 표현하지 않습니다.

## 요청 작성

### 파라미터와 요청 본문

| 대상 | 작성 규칙 |
| --- | --- |
| Path | 경로의 `{이름}`과 `name`을 일치시키고 `in: path`, `required: true`를 작성합니다. |
| Query | `in: query`와 `required`를 명시합니다. 생략 시 동작과 실제 기본값을 작성합니다. |
| Header | API 고유 헤더의 용도, 필수 여부와 예제를 작성합니다. `Accept`, `Content-Type`은 `content`로, Bearer 인증은 `security`로 표현하고 parameter로 중복 정의하지 않습니다. |
| JSON body | `requestBody.required`와 객체의 `required`를 구분합니다. 전자는 본문 자체, 후자는 본문 안의 필수 필드입니다. |
| 본문 없음 | `requestBody`를 만들지 않습니다. `{}`를 보내야 하는 것처럼 예제를 작성하지 않습니다. |

파라미터는 Path, Query, Header 순서로 배치합니다. Path는 URL에 나타나는 순서로, Query와 Header는 필수 항목부터 배치합니다. 같은 그룹에서는 해당 API의 사용 흐름에 맞는 순서를 유지합니다.

### 필드 설명

첫 문장은 필드의 업무상 의미를 설명합니다. 추가 설명은 입력 조건, 생략 시 동작, `null`과 빈 값의 의미 순서로 작성하되 해당하는 내용만 적습니다.

- `name: 이름` 대신 `여행 목록과 상세 화면에 표시할 여행 이름입니다`처럼 대상을 구체적으로 적습니다.
- `필수 문자열입니다`처럼 타입과 필수 표시를 반복하지 않습니다.
- 최소 및 최대 길이, 수치 범위와 정규식은 스키마에 작성합니다. 공백 허용, 대소문자 변환, 정규화와 업무상 의미는 설명에 적습니다.
- 단위가 있는 값은 단위와 기준을 적습니다. 비율의 기준값, 금액의 통화, 날짜의 경계와 시각의 시간대가 해당합니다.
- ID는 [ResourceId](../../src/main/resources/api-docs/common/components.yml)를 참조합니다. 숫자형 DB 키라는 이유로 `integer`나 숫자 전용 패턴을 추가하지 않습니다.
- `default`는 서버가 실제로 적용하는 기본값에만 사용합니다. 예시 입력값은 `example`에 적습니다.
- 선택 필드에는 생략 시 동작을 적습니다. PATCH는 값 유지와 제거를 구분하고, `null`을 허용하면 그 의미도 적습니다.
- 필드의 `example`과 parameter 최상위의 `example`을 중복 정의하지 않습니다. 공통 스키마의 예제가 해당 API에도 유효하면 그대로 사용합니다.

### 필수 여부와 null

현재 명세의 OpenAPI `3.0.3` 문법을 사용합니다. 다른 버전의 null 표현을 섞지 않습니다.

| 계약 | 스키마 작성 |
| --- | --- |
| 항상 포함하고 null 불가 | 객체의 `required`에 포함하고 `nullable`은 생략합니다. |
| 항상 포함하고 null 가능 | 객체의 `required`에 포함하고 필드에 `nullable: true`를 작성합니다. |
| 생략 가능하고 null 불가 | `required`에서 제외하고 `nullable`은 생략합니다. |
| 생략 가능하고 null 가능 | `required`에서 제외하고 필드에 `nullable: true`를 작성합니다. |

- `enum` 필드가 null을 허용하면 `nullable: true`와 함께 `enum` 값 목록에도 `null`을 포함합니다. `nullable: true`만으로는 `null`이 `enum` 제약에 걸려 거절됩니다.
- 객체는 [미정의 JSON 필드 거절 규칙](api-conventions.md#입력-검증)에 맞춰 `additionalProperties: false`를 작성합니다. 임의 키를 받는 계약이라면 허용할 값의 스키마를 별도로 정의합니다.
- 문자열은 확정된 `minLength`, `maxLength`, `pattern`, 수치는 `minimum`, `maximum`과 경계 포함 여부를 작성합니다.
- 배열은 `items`를 작성하고, 확정된 개수 제한이 있으면 `minItems`, `maxItems`를 작성합니다. 중복 거절이 계약인 경우에만 `uniqueItems: true`를 사용합니다.
- enum은 허용할 값을 `enum`에 모두 적고, 각 값의 업무상 의미를 설명합니다. 임의의 기본값이나 미지원 값을 예제에 넣지 않습니다.
- `format`만으로 모든 입력 검증이 구현됐다고 간주하지 않습니다. 명세의 제약과 실제 검증을 대조합니다.

### 목록과 조건부 입력

- 페이지네이션 파라미터는 공통 정의를 재사용합니다. API 설명에는 허용 정렬 필드, 기본 정렬과 검색 범위를 적습니다.
- 필터의 허용 값, 생략 시 동작과 조합 방식을 적습니다. 공통 규칙과 다른 조합은 해당 API에서 명시합니다.
- 특정 필드가 있을 때만 필수인 값, 서로 함께 보낼 수 없는 값과 필드 간 비교 조건은 관련 필드 설명에 적습니다. 확정된 경우 표현 가능한 스키마 제약도 함께 작성합니다.

### 배열과 객체 Query

- 배열과 객체 파라미터는 `style`, `explode`를 명시하고, `description`에 실제 Query 문자열을 적습니다. 스키마 예제는 직렬화 전의 배열 또는 객체 값으로 작성합니다.
- 반복 키 배열은 `style: form`, `explode: true`를 사용합니다. 쉼표 구분 배열이 실제 계약이면 `style: form`, `explode: false`를 사용합니다. 두 방식을 자동으로 함께 허용한다고 설명하지 않습니다.
- 빈 배열, 빈 값과 동일 키의 중복 처리는 생략과 구분해 적습니다. 서버가 거절하는 값은 정상 예제에 넣지 않습니다.
- 평면 객체를 `filter[name]=...` 형태로 받으면 `style: deepObject`, `explode: true`를 사용합니다. 대괄호는 실제 URL에서 퍼센트 인코딩합니다.
- 객체에 `style: form`, `explode: true`를 사용하면 객체 이름 없이 속성명이 Query 키가 됩니다. 다른 파라미터와 이름이 충돌하지 않는지 확인합니다.
- 중첩 객체나 배열 속성이 있는 객체의 직렬화를 `deepObject`만으로 정의했다고 간주하지 않습니다. 개별 파라미터로 분리하거나, 서버와 도구가 지원하는 별도 표현을 합의하고 실제 요청 문자열까지 명시합니다.
- 문서의 직렬화 방식과 Controller 바인딩, 클라이언트 요청이 일치하는지 HTTP 테스트로 확인합니다. 스키마 검증만으로 바인딩 성공을 판단하지 않습니다.

반복 키 배열과 평면 객체 필터의 가상 예시입니다. `status` 생략은 상태 필터 미적용이며, 빈 항목은 거절하는 계약입니다. `filter`는 생략할 수 있지만, 전송할 때는 두 속성을 모두 받는 계약입니다.

```yaml
parameters:
- name: status
  in: query
  required: false
  description: '조회할 상태입니다. DRAFT는 작성 중, CONFIRMED는 확정 상태이며 지정한 상태 중 하나에 해당하면 조회합니다. 생략하면 상태로 제한하지 않습니다. 중복 값은 허용하고 빈 값은 거절합니다. 예: status=DRAFT&status=CONFIRMED'
  style: form
  explode: true
  schema:
    type: array
    minItems: 1
    items:
      type: string
      enum: [DRAFT, CONFIRMED]
  example: [DRAFT, CONFIRMED]
- name: filter
  in: query
  required: false
  description: '이름과 활성 여부를 모두 만족하는 항목만 조회합니다. 생략하면 두 조건을 적용하지 않습니다. 예: filter%5Bname%5D=sample&filter%5BisActive%5D=true'
  style: deepObject
  explode: true
  schema:
    type: object
    required: [name, isActive]
    additionalProperties: false
    properties:
      name:
        type: string
        minLength: 1
        description: 조회할 이름입니다.
      isActive:
        type: boolean
        description: 조회할 항목의 활성 여부입니다.
  example:
    name: sample
    isActive: true
```

### 파일 업로드

- `requestBody.content.multipart/form-data.schema`에 각 파트를 속성으로 정의합니다. 단일 파일은 `type: string`, `format: binary`로 작성합니다. 파일명을 일반 문자열 예제로 넣거나 base64 문자열과 혼용하지 않습니다.
- JSON 파트를 함께 받으면 객체 스키마와 `encoding.<파트명>.contentType: application/json`을 작성합니다. 파일 파트의 허용 미디어 타입도 `encoding`에 작성합니다.
- 본문과 파트의 필수 여부를 구분합니다. 파일별 크기, 전체 요청 크기, 허용 형식과 파일 개수 제한 중 확정된 조건을 적습니다. 바이너리 크기를 문자열의 `maxLength`로 대신 표현하지 않습니다.
- 파일 형식 검증 방식과 실패 응답은 실제 계약에 맞춥니다. 크기 초과와 미디어 타입 오류는 [HTTP 오류 상태](api-conventions.md#http-오류-상태)에 따라 작성하고, 발생하지 않는 오류를 임의로 추가하지 않습니다.
- multipart 전체를 JSON 객체 예제로 대신하지 않습니다. 일반 파트의 값, 각 파트의 Content-Type과 파일 선택 방법을 실행 가능한 요청 형식으로 보여 줍니다. 실제 자격증명과 개인 파일은 포함하지 않습니다.

단일 PNG 파일과 JSON 설명을 함께 받는 가상 요청입니다. 아래 크기 제한은 예시 전용입니다.

```yaml
requestBody:
  required: true
  description: PNG 파일과 설명을 전송합니다. 파일은 최대 5 MiB(5,242,880바이트), 전체 요청은 최대 6 MiB(6,291,456바이트)입니다.
  content:
    multipart/form-data:
      schema:
        type: object
        required: [file, metadata]
        additionalProperties: false
        properties:
          file:
            type: string
            format: binary
            description: 업로드할 PNG 파일입니다.
          metadata:
            type: object
            description: 업로드할 파일의 설명입니다.
            required: [description]
            additionalProperties: false
            properties:
              description:
                type: string
                minLength: 1
                description: 파일 목록에 표시할 설명입니다.
                example: 예시 이미지
      encoding:
        file:
          contentType: image/png
        metadata:
          contentType: application/json
```

`sample.png`는 호출자가 준비한 가상 테스트 파일입니다. 인증과 응답은 해당 operation의 계약에 맞춰 별도로 작성합니다. multipart의 boundary는 클라이언트가 만들도록 전체 `Content-Type` 헤더를 직접 지정하지 않습니다.

```bash
curl 'https://example.com/api/v1/example-files' \
  --form 'file=@./sample.png;type=image/png' \
  --form 'metadata={"description":"예시 이미지"};type=application/json'
```

복수 파일은 파일 파트를 아래처럼 배열로 정의하고, 같은 파트 이름 `files`로 각 파일을 전송합니다. `minItems`, `maxItems`는 확정된 개수로 바꾸고 `encoding`의 키도 `files`에 맞춥니다. 아래는 1개 이상, 최대 3개를 받는 가상 예시입니다.

```yaml
files:
  type: array
  minItems: 1
  maxItems: 3
  items:
    type: string
    format: binary
  description: 함께 업로드할 파일 목록입니다.
```

## 조건부 요청과 응답

- 값만 달라지고 구조가 같으면 enum과 필드 설명을 사용합니다. 분기마다 필수 필드나 구조가 다르면 `oneOf`로 나눕니다. 둘 이상의 분기를 동시에 만족하는 것이 계약일 때만 `anyOf`를 사용합니다.
- 모든 조건을 동시에 만족해야 하는 합성은 `allOf`로 표현합니다. `allOf`를 필드 덮어쓰기나 여러 대안 중 하나를 선택하는 용도로 사용하지 않습니다.
- 구분 필드가 있는 계약은 `discriminator.propertyName`과 값별 `mapping`을 작성합니다. 각 분기는 이름 있는 component로 정의하고, 구분 필드를 `required`에 포함하며 해당 값 하나만 허용하는 `enum`을 작성합니다.
- `discriminator`만으로 필드 검증을 대신하지 않습니다. 각 분기는 자체 필수 필드와 제약을 가지며, 분기 예제가 정확히 하나의 `oneOf` 스키마만 만족해야 합니다.
- 분기별 객체는 필요한 공통 필드까지 포함하고 `additionalProperties: false`를 작성합니다. 닫힌 공통 객체를 `allOf`로 확장해 새 속성까지 거절하게 만들지 않습니다. OpenAPI 3.0.3에 없는 `const`, `if`/`then`/`else`, `unevaluatedProperties`는 사용하지 않습니다.
- 필드 간 날짜 비교나 합계처럼 스키마로 표현하지 못하는 조건은 관련 필드 설명과 오류 응답에 적고 HTTP 테스트로 검증합니다.
- 요청은 `requestBody`에, 응답은 실제로 달라지는 `data` 또는 배열 원소에 분기 스키마를 연결합니다. 응답의 공통 포장 구조는 유지합니다.
- 각 분기마다 전체 본문 예제를 작성합니다. 대표 분기 하나의 예제로 다른 분기를 대신하지 않습니다.

다음은 텍스트 또는 링크 항목 하나를 받는 가상 JSON 요청입니다. `requestBody`는 operation 안에, `components`는 도메인 파일 최상위에 배치합니다. 기존 `components.schemas`에 통합하고 참조 경로를 맞춥니다.

```yaml
requestBody:
  required: true
  description: 텍스트 또는 링크 항목 하나를 전송합니다.
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/ExampleItemInput'
      examples:
        textItem:
          summary: 텍스트 항목
          value:
            type: TEXT
            text: 예시 내용
        linkItem:
          summary: 링크 항목
          value:
            type: LINK
            url: https://example.com/items/sample
components:
  schemas:
    ExampleItemInput:
      description: 구분 값에 따라 텍스트 또는 링크 필드를 받습니다.
      oneOf:
      - $ref: '#/components/schemas/ExampleTextItem'
      - $ref: '#/components/schemas/ExampleLinkItem'
      discriminator:
        propertyName: type
        mapping:
          TEXT: '#/components/schemas/ExampleTextItem'
          LINK: '#/components/schemas/ExampleLinkItem'
    ExampleTextItem:
      type: object
      required: [type, text]
      additionalProperties: false
      properties:
        type:
          type: string
          enum: [TEXT]
          description: 텍스트 항목을 구분하는 값입니다.
        text:
          type: string
          minLength: 1
          description: 표시할 텍스트입니다.
    ExampleLinkItem:
      type: object
      required: [type, url]
      additionalProperties: false
      properties:
        type:
          type: string
          enum: [LINK]
          description: 링크 항목을 구분하는 값입니다.
        url:
          type: string
          format: uri
          description: 이동할 링크 주소입니다.
```

## 성공 응답 작성

- 상태 코드별 `description`은 `초대 정보 조회 성공`처럼 결과를 식별할 수 있는 짧은 명사형으로 작성합니다. 상태 코드를 다시 적지 않습니다.
- JSON 응답은 `content.application/json.schema`에 공통 응답을 포함한 전체 본문 구조를 정의합니다. 업무 데이터만 스키마로 지정하지 않습니다.
- `data`의 구체적인 타입과 null 허용 여부를 API별로 정합니다. 공통 `ApiResponse`만 참조해 타입을 비워 두지 않습니다.
- 응답 필드의 포함 여부와 null 가능성을 구분합니다. 단일 값 없음, 빈 목록과 조건부 필드는 [공통 규칙](api-conventions.md#필수-입력과-선택-입력)을 따릅니다.
- 중첩 객체와 배열 원소까지 의미와 제약을 작성합니다. `data` 같은 공통 포장 필드는 별도 업무 의미가 없으면 설명을 반복하지 않습니다.
- 생성 응답의 `Location`, 다운로드의 `Content-Disposition` 등 계약상 필요한 헤더는 `headers`에 스키마, 설명과 예제를 작성합니다.
- `204`는 `description`을 작성하고 계약상 필요한 응답 헤더가 있으면 `headers`에 정의합니다. `content`, 빈 객체와 `data: null` 예제를 넣지 않습니다.
- 비동기 응답은 처리 완료가 아니라 접수 결과와 이후 확인 방법을 설명합니다.

### 파일 다운로드

- 성공 응답의 `content`에는 실제 미디어 타입과 `type: string`, `format: binary`를 작성합니다. JSON 공통 응답으로 감싸지 않습니다.
- 파일명과 다운로드 방식을 `Content-Disposition` 헤더 설명과 예제로 작성합니다. 고정된 미디어 타입이면 `application/octet-stream`으로 뭉뚱그리지 않습니다.
- 파일 바이트 대신 미디어 타입, 헤더와 저장 방법을 보여 줍니다. 실패 응답이 JSON이면 해당 상태 코드에 별도의 `application/json`과 공통 오류 스키마를 연결합니다.
- 임시 다운로드 URL을 JSON으로 반환하는 API는 파일 다운로드 응답이 아닙니다. JSON 필드로 URL과 확정된 유효 기간 또는 만료 시각을 설명합니다.

PNG 파일을 직접 반환하는 가상 응답입니다.

```yaml
responses:
  '200':
    description: 파일 다운로드 성공
    headers:
      Content-Disposition:
        description: 첨부 파일로 저장할 때 사용할 파일명입니다.
        schema:
          type: string
        example: 'attachment; filename="sample.png"'
    content:
      image/png:
        schema:
          type: string
          format: binary
```

```bash
curl 'https://example.com/api/v1/example-files/sample/content' \
  --dump-header ./headers.txt \
  --output ./sample.png
```

## 오류 응답 작성

### 상태 코드와 발생 조건

- 해당 API에서 발생하는 것으로 확정된 오류를 작성합니다. 공통 오류 전체를 모든 API에 기계적으로 나열하지 않습니다.
- HTTP 상태는 [HTTP 오류 상태](api-conventions.md#http-오류-상태)를 따르고, 본문 스키마는 공통 `ErrorResponse`를 참조합니다.
- 오류가 하나이면 `description` 첫 문장에 발생 조건을 적고, 다음 문단에 `**오류 코드:**`와 코드 식별자를 적습니다.
- 같은 HTTP 상태에 여러 오류 코드가 있으면 `오류 코드 | 발생 조건` 표로 작성합니다. 오류 코드를 쉼표로 묶은 긴 문장으로 나열하지 않습니다.
- `401`은 인증 실패, `403`은 인증된 사용자의 권한 부족으로 구분합니다. `403`에는 이 API에서 요구하는 역할이나 소유 관계를 적습니다.
- `404`에는 찾지 못한 대상을 적습니다. 권한 부족을 `404`로 숨기는 등의 정책은 확정된 경우에만 작성합니다.
- `400`은 입력 제약과 연결하고 `409`는 충돌하는 업무 상태와 연결합니다. `요청 오류`, `충돌 발생`만으로 설명을 끝내지 않습니다.
- 여러 조건이 동시에 실패할 때 어떤 오류가 우선하는지는 확정된 경우에만 적습니다.
- `description`에 `400:`처럼 상태 코드를 반복하지 않습니다. 사용자 표시 문구는 예제의 `message`, 개발자가 판단할 조건은 `description`에 적습니다.

### 오류 예제

- 문서에 정의한 오류 코드마다 `examples`에 이름 있는 예제를 작성합니다. 이름은 실제 오류 코드와 일치시킵니다.
- `summary`는 `여행이 이미 확정됨`처럼 상황을 짧게 적습니다. `value`에는 `code`, `message`, `errors`를 모두 작성합니다.
- `message`와 필드 오류의 `reason`은 확정된 사용자 표시 문구를 사용합니다. 구현이 있으면 오류 코드 정의와 일치시킵니다. 문구가 정해지지 않은 오류는 확정된 계약으로 추가하지 않습니다.
- 필드 검증 오류 예제는 실제 요청 필드명을 사용합니다. 필드 오류가 없으면 `errors: null`을 적고 빈 배열로 대체하지 않습니다.
- 필드 제약이 여러 개인 API는 대표 검증 실패 예제에 위반 필드와 사유를 보여 줍니다. 인증 실패나 업무 상태 충돌에 관계없는 필드 오류를 넣지 않습니다.

## 예제 작성

### 위치와 이름

| 예제 | 위치와 이름 |
| --- | --- |
| 필드의 대표 값 | 해당 스키마의 `example` |
| 요청 전체 | 미디어 타입의 `examples.request`, 분기별로는 `examples.textItem`처럼 구별 |
| 기본 성공 응답 | 미디어 타입의 `examples.success` |
| 빈 목록 등 추가 성공 상황 | `examples.emptyResult`처럼 상황을 나타내는 lowerCamelCase |
| 오류 응답 | 미디어 타입의 `examples.<오류 코드>` |

- 미디어 타입에서는 `example`과 `examples`를 함께 쓰지 않습니다. 전체 요청과 응답은 이름 있는 `examples`로 통일합니다.
- 각 Example Object에 `summary`와 `value`를 작성합니다. `summary`로 구분되지 않는 사용 조건이 있을 때만 `description`을 추가합니다.
- JSON 또는 텍스트 본문이 있는 요청과 성공 응답에는 최소 하나의 전체 예제를 작성합니다. 빈 목록, 값 없음과 조건부 필드가 다른 화면 처리를 요구하면 해당 예제도 추가합니다. 구조가 다른 분기는 분기별 예제를 작성합니다.
- binary와 multipart는 전체 본문 예제 의무에서 제외하고 [파일 업로드](#파일-업로드), [파일 다운로드](#파일-다운로드)의 요청 형식과 헤더 예제로 대체합니다. 파일 바이트를 `value`에 넣지 않습니다. multipart의 일반 파트 값은 생략하지 않습니다.
- 전체 예제에는 공통 포장 필드와 중첩 객체까지 포함합니다. 스키마 속성의 예제만으로 대체하지 않습니다.
- 예제는 필수 필드, 타입, enum, 패턴과 범위를 만족해야 합니다. 서로 연관된 값도 일치시킵니다. 초대 코드 `A7K2PQ`를 사용하면 링크의 코드도 `A7K2PQ`로 맞춥니다.
- ID와 금액은 문자열, boolean은 boolean, `null`은 null로 작성합니다. `"null"`, `"true"`처럼 다른 타입으로 바꾸지 않습니다.
- 날짜, URL과 코드는 가상 값을 사용합니다. `example.com`의 링크를 실제 서비스 주소로 설명하지 않습니다.
- 헤더나 예제에 실제 토큰을 넣지 않습니다. 인증은 Scalar의 인증 입력 기능으로 설정합니다.

## 공통 정의와 참조

- 여러 API에서 의미와 구조가 같은 스키마는 재사용합니다. 구조가 같아도 업무 의미나 입력 제약이 다르면 하나로 합치지 않습니다.
- 같은 도메인 안에서만 재사용하면 해당 도메인의 `components`에 둡니다. 여러 도메인의 공통 계약만 `common/components.yml`에 둡니다.
- 단일 API에서만 사용하는 응답 포장 객체는 인라인으로 작성할 수 있습니다. 중첩 업무 모델은 이름 있는 스키마로 정의합니다.
- 공통 response는 상태 코드뿐 아니라 발생 조건과 예제까지 같을 때 그대로 참조합니다. 권한이나 실패 대상 설명이 다르면 operation에 response를 작성하고 공통 `ErrorResponse` 스키마를 참조합니다.
- OpenAPI `3.0.3`의 Reference Object 옆에 `description`, `nullable`이나 제약을 덧붙여 덮어쓰지 않습니다. 추가 설명과 제약이 필요하면 스키마의 `allOf`로 감쌉니다. `nullable`만 추가하면 참조 대상의 null 불가 제약을 해제할 수 있다고 간주하지 않습니다. null 허용이 필요한 스키마는 검증기와 Scalar에서 함께 확인합니다.
- 동일 예제를 여러 API가 공유하면 `components.examples`에 정의합니다. 전체 예제는 Example Object를 참조하고, 단순 `value`나 기존 `example` 값에 `$ref`를 걸지 않습니다.
- 참조 파일을 옮기거나 이름을 바꾸면 루트부터 해당 정의까지 모든 상대 경로와 JSON Pointer를 확인합니다.

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

[scalar-initializer.js](../../src/main/resources/api-docs/scalar/scalar-initializer.js)는 `/api-docs/openapi.yml`을 읽고 상대 경로의 도메인별 YAML과 공통 정의를 `$ref`로 불러옵니다. 빌드에서 명세를 합치거나 검증하지 않습니다. 명세 변경 후에는 `processResources` 또는 `bootRun`으로 리소스를 다시 복사합니다. `openapi.yml`의 `servers: [{ url: / }]`는 같은 서버로 API 요청을 보냅니다. 환경별 주소를 JavaScript에 하드코딩하지 않습니다.

Scalar는 [WebJar](../../build.gradle.kts)의 standalone 번들을 서버에 포함해 제공하고 [ApiDocsConfig](../../src/main/kotlin/com/ject/global/config/ApiDocsConfig.kt)가 UI 리소스를 매핑합니다. WebJar 버전을 바꾸면 설정 클래스의 리소스 경로도 함께 변경합니다. 기존 문서 주소인 `/swagger-ui.html`에서 Scalar를 제공합니다.

인증 정보는 브라우저 저장소에 유지하지 않습니다. 외부 CDN, 기본 웹 폰트와 요청 프록시를 사용하지 않으며 UI의 API 실행은 실제 서버에 직접 요청을 보냅니다.

환경별 활성화는 [환경 설정](../operations/configuration.md#api-문서)을 따릅니다.

## 변경 후 확인

- 제목, 설명 절, 키 순서와 예제 이름이 작성 규칙을 따르는지 확인합니다.
- 필수 여부와 null, 생략과 빈 값, 단위, 기본값과 검증 조건을 호출자가 구분할 수 있는지 확인합니다.
- 오류 설명의 조건, HTTP 상태, 오류 코드와 예제의 대응을 확인합니다.
- YAML 파싱, `$ref` 해석, `operationId` 중복과 예제의 스키마 일치를 확인합니다. 파싱 성공만으로 계약 일치를 판단하지 않습니다.
- 조건부 구조는 모든 분기의 정상 예제와 구분 값 누락, 미지원 값, 분기 필드 혼합 등 실패 입력을 확인합니다. `oneOf`는 각 정상 예제가 정확히 한 분기를 만족하는지 확인합니다.
- 배열과 객체 Query는 문서의 URL 예시와 실제 바인딩을, 파일 전송은 파트 이름, 미디어 타입, 제한과 다운로드 헤더를 HTTP 테스트로 확인합니다. 파일 크기나 업무 조건을 스키마 검사만으로 검증했다고 보고하지 않습니다.
- 활성 환경에서 Scalar가 루트 YAML과 참조 파일을 불러오고 경로와 스키마를 표시하는지 확인합니다.
- 비활성 환경에서 `/swagger-ui.html`, `/api-docs/openapi.yml`과 도메인별 YAML이 제공되지 않는지 확인합니다.
- API 구현 시 주요 성공과 실패 응답을 HTTP 테스트로 검증하고 명세와 대조합니다.
