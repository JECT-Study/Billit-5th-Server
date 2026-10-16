# 오브젝트 스토리지

## 사용 범위

Oracle Object Storage의 S3 호환 API를 사용합니다. 공통 클라이언트는 이미지에 한정하지 않고 객체 단위로 접근합니다. 프로필 이미지와 영수증의 업로드 정책은 각 업무에서 정합니다. 로그 수집과 보관은 현재 범위에 포함하지 않습니다.

[ObjectStorage](../../src/main/kotlin/com/ject/external/storage/ObjectStorage.kt)는 임시 업로드 URL, 임시 다운로드 URL, 객체 메타데이터 확인과 삭제를 제공합니다. 별도 Gradle 모듈을 만들지 않고 `external/storage` 패키지에서 관리합니다. [S3ObjectStorage](../../src/main/kotlin/com/ject/external/storage/s3/S3ObjectStorage.kt)가 AWS SDK for Java 2.x 호출을 담당하며 SDK 타입은 공통 계약 밖으로 전달하지 않습니다.

업무별 Port와 Adapter는 [외부 서비스 연동](../contributing/architecture.md#외부-서비스-연동)에 따라 필요한 시점에 추가합니다. HTTP API, 업무별 저장소와 DB 연동은 아직 제공하지 않습니다.

## 임시 접근 URL

S3 presigned URL을 특정 버킷과 객체 키에 대해 생성합니다. 업로드는 HTTP `PUT`, 다운로드는 HTTP `GET`을 사용합니다. URL 서명은 SDK가 로컬에서 생성하며 발급 자체는 저장소에 요청하거나 객체 존재 여부를 확인하지 않습니다. OCI 네이티브 API의 PAR은 생성하지 않습니다.

호출하는 업무에서 만료 기간을 지정합니다. SigV4 서명 범위에 따라 1초 이상 7일 이하를 허용하며, 반환하는 [StorageAccessUrl](../../src/main/kotlin/com/ject/external/storage/StorageAccessUrl.kt)의 만료 시각은 SDK가 반환한 서명 만료 시각입니다. 서명에 사용하는 현재 시각도 SDK가 관리하므로 실행 서버의 시간을 동기화합니다.

URL 소지자는 만료 전까지 지정된 메서드로 반복 사용할 수 있습니다. 업로드 URL은 같은 객체의 덮어쓰기도 허용하므로 업로드 완료 확인만으로 이후 변경이 차단되지는 않습니다. 검증 후 파일을 고정해야 하는 업무는 별도 최종 키로 복사하거나 객체 버전을 고정하는 등 완료 절차를 결정합니다. 현재 공통 클라이언트는 개별 URL의 조기 취소, 복사나 객체 버전 고정을 제공하지 않습니다.

Presigned URL과 자격증명은 로그에 기록하지 않습니다. 반환 객체의 `toString()`은 URL을 숨기지만 `url` 필드나 API 응답을 직접 기록해서는 안 됩니다. DB에는 만료되는 URL 대신 객체 키를 저장합니다.

## 업로드와 업무 연결

1. 업무에서 요청자의 업로드 권한을 확인하고 충돌하지 않는 객체 키를 생성합니다. 원본 파일명이나 개인정보를 키로 그대로 사용하지 않습니다.
2. 공통 클라이언트가 해당 키의 업로드 URL을 발급합니다.
3. 클라이언트가 URL에 파일을 직접 `PUT`합니다. 브라우저 업로드를 허용하려면 버킷 CORS를 허용할 프론트 주소, 메서드와 헤더에 맞게 설정합니다.
4. 클라이언트가 PUT 성공 후 서버에 업로드 완료를 알립니다. 업무에서 요청자와 발급했던 객체 키의 소유 관계를 확인하고 `head`로 실제 객체의 크기, Content-Type과 ETag를 조회합니다.
5. 업무에서 필요한 검증을 마친 뒤 객체 키를 DB의 프로필이나 영수증과 연결합니다.

URL 발급이나 클라이언트의 완료 통지만으로 업로드 성공을 확정하지 않습니다. 완료 요청이 없으면 서버는 자동으로 업로드 여부를 알지 못합니다. 현재 공통 클라이언트는 `head`만 제공하며, 완료 API와 발급 대상 관리 방식은 해당 업무에서 구현합니다.

[ObjectMetadata](../../src/main/kotlin/com/ject/external/storage/ObjectMetadata.kt)는 객체의 메타데이터만 표현합니다. Content-Type은 업로더가 지정할 수 있으므로 실제 이미지 형식이나 안전성을 증명하지 않습니다. 허용 형식, 최대 크기, 실제 파일 검사, 공개 범위와 보관 기간은 업무별로 정합니다. 현재 업로드 URL은 파일 크기나 Content-Type을 제한하지 않습니다.

다운로드 URL은 업무에서 조회 권한을 확인한 뒤 발급합니다. 비공개 버킷을 사용하고, 프로필 이미지와 영수증의 공개 범위를 공통 클라이언트에서 결정하지 않습니다.

오브젝트 스토리지와 PostgreSQL 변경은 하나의 원자적 트랜잭션이 아닙니다. 새 파일의 DB 연결 실패, 기존 파일 교체와 삭제 실패, 미완료 업로드의 정리 순서와 재시도는 업무 기능에서 결정합니다. `delete`는 해당 키에 삭제를 요청하며 버전 관리와 보관 규칙에 따라 과거 버전이 남을 수 있습니다.

## 설정과 인증

네 프로필의 `app.storage`에서 설정합니다. 기본값은 비활성화이며 `STORAGE_ENABLED=true`일 때만 S3 클라이언트와 서명기를 생성합니다. 비활성 상태에서는 `ObjectStorage` Bean을 제공하지 않습니다. 저장소가 필요한 업무를 연결하면 해당 실행 환경에서 활성화해야 합니다.

| 환경변수 | local 기본값 | dev, prod |
| --- | --- | --- |
| `STORAGE_ENABLED` | `false` | 기본값은 `false`입니다. |
| `STORAGE_S3_ENDPOINT` | `http://localhost:9090` | 활성화 시 필수입니다. OCI S3 호환 endpoint를 지정합니다. |
| `STORAGE_S3_BUCKET` | `billit-local` | 활성화 시 필수입니다. 환경별 버킷을 구분합니다. |
| `STORAGE_S3_REGION` | `us-east-1` | 활성화 시 필수입니다. 버킷이 위치한 OCI 리전을 지정합니다. |
| `STORAGE_S3_ACCESS_KEY` | `local` | 활성화 시 필수입니다. Customer Secret Key의 Access Key입니다. |
| `STORAGE_S3_SECRET_KEY` | `local` | 활성화 시 필수입니다. Customer Secret Key의 Secret Key입니다. |

별도 OCI 인증 파일이나 AWS 자격증명 파일을 읽지 않습니다. dev와 prod에서는 OCI 사용자의 Customer Secret Key와 해당 사용자에게 부여한 IAM 권한으로 인증합니다. Instance Principal을 사용하지 않습니다. 업로드를 위한 `OBJECT_CREATE`, `OBJECT_OVERWRITE`, 다운로드와 객체 확인을 위한 `OBJECT_READ`, 삭제를 위한 `OBJECT_DELETE` 권한을 대상 버킷으로 제한합니다.

OCI 콘솔과 공식 문서에서 해당 리전의 S3 호환 endpoint를 확인하고 HTTPS 주소를 주입합니다. endpoint에는 버킷이나 객체 경로, 서명 쿼리를 포함하지 않습니다. SDK는 path-style URL을 사용합니다. 클라이언트의 `PUT`, `GET`에도 같은 endpoint가 포함되므로 클라이언트에서 접근 가능한 주소여야 합니다. URL 발급 후 host나 경로를 변경하면 서명이 맞지 않습니다.

연결 timeout은 `2s`, 응답 읽기 timeout은 `5s`로 `S3StorageConfig`에 고정합니다. SDK 자동 재시도는 비활성화하며 업무별 재시도는 멱등성과 실패 결과를 확인한 뒤 정합니다. SDK 버전은 Gradle BOM으로 고정합니다. 버전을 변경하면 체크섬, 서명과 요청 인코딩을 포함한 dev 검증을 다시 실행합니다.

## 로컬과 자동화 테스트

로컬 개발용 OCI 자격증명은 구성하지 않습니다. S3 호환 저장소가 필요하면 무료 오픈소스인 Adobe S3Mock을 사용할 수 있습니다.

```bash
docker run --rm -d --name billit-storage \
  -p 127.0.0.1:9090:9090 \
  -e COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS=billit-local \
  adobe/s3mock:5.2.3
STORAGE_ENABLED=true SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
docker stop billit-storage
```

이 컨테이너는 개발용이며 종료 시 데이터가 유실됩니다. `test` 프로필은 저장소를 비활성화하며 환경변수의 운영 자격증명을 참조하지 않습니다. 업무 통합 테스트에서는 [IntegrationTestSupport](../../src/test/kotlin/com/ject/support/IntegrationTestSupport.kt)가 PostgreSQL, Redis와 S3Mock을 함께 실행하며 S3Mock 접속 정보와 저장소 활성화를 주입합니다. 실패 분류와 원인 예외 보존은 SDK 예외를 주입하는 단위 테스트로 확인합니다. Presigned URL의 구성과 입력 검증도 HTTP 서버 없이 검증합니다.

S3Mock은 Presigned URL을 받아들이지만 서명, HTTP 메서드와 만료를 검증하지 않습니다. 로컬 검증은 OCI IAM, 서명 유효성, 만료와 CORS 검증을 대체하지 않습니다.

## dev 검증

dev 전용 비공개 버킷과 해당 버킷에만 접근할 수 있는 Customer Secret Key로 presigned PUT, HEAD, presigned GET과 내용 비교, 만료 후 거절과 삭제를 확인합니다. 브라우저 CORS와 Presigned URL에 다른 HTTP 메서드를 사용했을 때의 거절도 확인합니다. 검증에 사용한 객체는 정리하며 버전 관리가 활성화된 버킷에서는 과거 버전과 삭제 마커가 남을 수 있습니다.

## 실패 처리

저장소 요청 실패는 기술적인 원인을 분류한 `ObjectStorageException`으로 전달합니다. 작업 종류, 실패 사유, 저장소 HTTP 상태, 서비스 오류 코드와 요청 ID를 제공하고 원인 SDK 예외는 `cause`로 보존합니다. 응답을 받지 못한 실패의 HTTP 상태는 `null`입니다. 모든 SDK 클라이언트 오류를 연결 실패로 간주하지 않고 원인 예외로 연결 실패와 timeout을 확인합니다.

| 실패 사유 | 분류 기준 | 업무에서 처리하지 않았을 때의 API 응답 |
| --- | --- | --- |
| `UNAVAILABLE` | 확인된 연결 실패와 timeout, 저장소의 408, 429, 500, 502, 503, 504 또는 일시적 장애 코드 | `503`, `COMMON_SERVICE_UNAVAILABLE` |
| `CONFIGURATION` | 저장소의 401, 403, 버킷 없음, 자격증명과 서명 또는 지원하지 않는 기능 오류 | `500`, `COMMON_INTERNAL_SERVER_ERROR` |
| `NOT_FOUND` | 설정 오류로 식별되지 않은 저장소의 404 | `500`, `COMMON_INTERNAL_SERVER_ERROR` |
| `UNKNOWN` | 원인을 분류하지 못한 실패 | `500`, `COMMON_INTERNAL_SERVER_ERROR` |

`NOT_FOUND`만으로 업로드 미완료나 사용자가 요청한 리소스 없음을 확정하지 않습니다. 버킷과 권한 설정, 발급했던 객체 키를 업무에서 확인하고 필요한 업무 오류로 변환합니다. 저장소의 403과 404를 사용자 API의 403과 404로 그대로 전달하지 않습니다.

[공통 예외 처리기](../../src/main/kotlin/com/ject/global/error/GlobalExceptionHandler.kt)는 저장소 실패 분류, 상태, 서비스 오류 코드, 요청 ID와 최하위 원인 예외의 클래스명을 한 번 기록합니다. 원인 예외의 메시지와 스택 전체는 기본 로그에 출력하지 않으며 사용자에게는 [공통 오류 응답](../contributing/api-conventions.md#실패-응답)만 반환합니다. 503의 메시지는 `일시적으로 요청을 처리할 수 없습니다.`, 500의 메시지는 `서버 오류가 발생했습니다.`입니다. 503 응답이 자동 재시도를 허용한다는 의미는 아닙니다. 재시도는 업무별 멱등성과 부수 효과를 확인해 결정합니다.

`delete`는 저장소가 반환한 성공을 그대로 처리합니다. S3의 존재하지 않는 키 삭제는 성공할 수 있으므로 삭제 성공이 이전 객체의 존재를 증명하지는 않습니다. 저장소에서 오류 응답을 받으면 임의로 성공 처리하지 않습니다.

Presigned URL로 직접 수행하는 PUT과 GET은 백엔드 예외 처리기를 거치지 않습니다. 클라이언트에서 저장소의 실패 응답과 네트워크 오류를 처리하며 성공하지 않은 업로드를 완료로 표시하지 않습니다.

## 참고

- [OCI S3 호환 API와 인증](https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/s3compatibleapi.htm)
- [OCI 지원 S3 API](https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/s3compatibleapi_topic-Amazon_S3_Compatibility_API_Support.htm)
- [S3 presigned URL](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)
- [Adobe S3Mock과 제한 사항](https://github.com/adobe/S3Mock)
