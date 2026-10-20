# 에이전트 작업 가이드

Kotlin, Spring Boot, Spring Data JPA와 PostgreSQL을 사용합니다. 코드 패키지와 Gradle group은 `com.ject`로 유지합니다.

## 문서 확인

[개발 가이드](docs/README.md)와 작업별 문서를 확인합니다. 지침이 없으면 기존 구현과 확정된 요구사항을 확인하고, 다른 프로젝트의 규칙을 임의로 적용하지 않습니다.

| 작업 | 읽을 문서 |
| --- | --- |
| 기능 설계와 구조 변경 | [아키텍처](docs/contributing/architecture.md) |
| HTTP API 요청, 응답과 공통 규칙 | [API 공통 규칙](docs/contributing/api-conventions.md) |
| API 계약과 Scalar 변경 | [API 문서 가이드](docs/contributing/api-documentation.md) |
| 문서 작성과 수정 | [문서 컨벤션](docs/contributing/documentation.md) |
| Kotlin 코드와 Gradle Kotlin DSL 작성 | [코드 스타일](docs/contributing/code-style.md) |
| Entity, Repository, 스키마와 트랜잭션 변경 | [영속성 가이드](docs/contributing/persistence.md) |
| 테스트 작성과 수정 | [테스트 가이드](docs/contributing/testing.md) |
| 기동, 설정 클래스와 환경별 설정 변경 | [환경 설정](docs/operations/configuration.md) |
| 날짜, 저장 시각과 API 시간 표현 | [시간 처리](docs/operations/time.md) |
| Redis 연결, 인증 데이터, 캐시와 잠금 | [Redis 가이드](docs/operations/redis.md) |
| 오브젝트 스토리지, 임시 접근 URL과 S3 인증 | [오브젝트 스토리지 가이드](docs/operations/storage.md) |
| 로그와 요청 추적 | [로깅](docs/operations/logging.md) |

문서와 구현이 충돌하면 차이를 명시하고, 확정된 결정에 따라 문서와 구현을 함께 갱신합니다.

HTTP API를 구현하거나 수정하기 전에 [API 공통 규칙](docs/contributing/api-conventions.md), [루트 명세](src/main/resources/api-docs/openapi.yml), 해당 업무의 `src/main/resources/api-docs/domains/<업무>.yml`과 [명세와 구현 동기화 규칙](docs/contributing/api-documentation.md#명세와-구현-동기화)을 확인합니다.

## 미결정 정책

문서에 없는 정책을 기존 코드나 관례로 확정하지 않습니다. 선택에 따라 사용자 결과, 권한, 데이터 보관, 동시성 처리나 외부 부수 효과가 달라지면 구현 전에 사용자에게 확인합니다.

- 확인한 사실, 미결정 조건, 선택지와 결과를 구분해 제시합니다.
- 질문은 묶어서 전달하고, 답변과 무관한 조사와 작업은 계속합니다.
- 답변 전에는 정책을 구현하거나 테스트에 고정하지 않습니다.
- 확정된 원칙으로 판단할 수 있는 구현 선택은 직접 결정하고 근거를 설명합니다.
- 정책이 확정되면 정책서, 구현과 테스트를 같은 변경에서 갱신합니다.

## 작업 절차

이슈, 브랜치, 커밋과 PR은 [Git 작업 규칙](docs/contributing/git-workflow.md)을 따릅니다.

1. 대상 업무 영역과 데이터 소유자를 식별하고 업무 정책을 확인합니다.
2. 유스케이스의 입력, 출력, 조회, 변경, 트랜잭션과 외부 부수 효과를 정리합니다.
3. [아키텍처](docs/contributing/architecture.md)를 기준으로 UseCase, Repository, 협력 객체와 외부 연동 Port의 책임을 정합니다.
4. 주변 코드의 패턴을 확인하고 구현합니다. 불필요한 클래스나 인터페이스는 추가하지 않습니다.
5. 주요 데이터 흐름, 트랜잭션 경계와 실패 경로를 설명합니다.
6. 실패 시 영향이 큰 가정을 중심으로 테스트합니다. SQL과 DB 동작은 가능한 한 실제 대상 DB에서 검증합니다.
7. 원칙의 예외나 장기적인 절충이 필요하면 근거와 대안을 제시하고, 결정된 내용을 문서에 반영합니다.

## 변경 범위

- 요청하지 않은 업무 예제나 프레임워크 의존성을 추가하지 않습니다.
- JPA 외의 영속성 기술과 분산 일관성 방식은 명시적인 결정 없이 도입하지 않습니다. 업무 간 접근은 [아키텍처](docs/contributing/architecture.md#업무-간-접근)를 따릅니다.
- 생성된 코드를 직접 수정하지 않습니다.
- 외부 SDK 타입, API DTO와 JPA Entity를 다른 경계로 불필요하게 전파하지 않습니다.
- 테스트만을 위한 인터페이스, 범용 Repository나 Service, 의미 없는 `global` 추상화를 만들지 않습니다.
- 검증된 라이브러리가 제공하는 보안 기능이나 프로토콜을 직접 구현하지 않습니다.
- 패키지를 확장하거나 도메인 추상화의 이름을 정할 때는 근거, 소유자와 대안을 제시하고 사용자와 합의합니다.

## 실행과 검증

### JDK 선택

Gradle 실행 전 Java 21을 선택합니다. Java toolchain 설정과 별도로 Gradle 실행 JVM을 지정합니다.

- SDKMAN을 사용하면 `.sdkmanrc`에 따라 `sdk env`를 실행합니다.
- SDKMAN을 사용하지 않으면 설치된 Java 21의 경로를 `JAVA_HOME`에 지정합니다.
- Java 21이 없으면 사용자에게 설치나 사용할 JDK 경로를 확인합니다.

### Gradle 명령

저장소 루트에서 실행합니다.

```bash
sdk env
./gradlew bootRun
./gradlew test
./gradlew spotlessApply
./gradlew spotlessCheck
```

프로필 선택은 [환경 설정](docs/operations/configuration.md#프로필-설정-파일)을 따릅니다.

커밋 전에는 변경 파일 종류와 관계없이 `spotlessApply`를 실행하고 결과를 검토합니다. 커밋과 PR은 사용자가 요청한 범위에서 진행합니다.

### 캐시 접근 오류

Gradle이 `~/.gradle`의 wrapper나 캐시 잠금 파일에 접근하지 못하면 캐시를 삭제하지 않습니다. 해당 경로의 접근 권한을 요청하고 같은 명령을 다시 실행합니다. 별도의 쓰기 가능한 캐시를 사용해야 하는 환경에서는 `GRADLE_USER_HOME`을 지정합니다.

## 검토 결과

코드와 설계 검토에서는 변경에 해당하는 항목을 설명합니다.

- 시작점부터 결과까지의 데이터 흐름과 부수 효과의 순서
- 조회하거나 변경하는 테이블, 데이터 소유자와 트랜잭션 경계
- 업무 불변식을 보장하는 코드와 DB 제약조건
- 실제 또는 예상 SQL과 동시성 위험
- 외부 연동 실패, 재시도와 멱등성
- 프레임워크의 암묵적 동작과 검증 방법

중요한 결정에는 비용, 대안과 변경 조건을 함께 제시합니다.
