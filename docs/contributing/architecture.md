# 아키텍처

## 배포 단위

백엔드는 하나의 Spring Boot 애플리케이션으로 배포하는 모놀리스로 구성합니다. 애플리케이션 내부는 업무별로 구분합니다.

## 최상위 패키지

```text
com.ject
├── module
│   └── <업무>
├── external
└── global
```

| 패키지 | 책임 |
| --- | --- |
| `module` | 업무별 코드와 데이터 소유권을 구분합니다. Gradle 멀티모듈을 의미하지 않습니다. |
| `external` | 외부 서비스의 기술 클라이언트를 둡니다. |
| `global` | 업무에 속하지 않는 공통 설정, 로깅과 오류 계약을 둡니다. |

## 업무 영역

인증, 사용자, 여행, 지출과 정산은 각각 독립된 업무 영역으로 구분합니다. 지출과 정산을 여행의 하위 패키지에 넣지 않습니다.

```text
module/
├── auth
├── user
├── trip
├── expense
└── settlement
```

| 영역 | 책임 |
| --- | --- |
| `auth` | 로그인, 인증과 세션을 담당합니다. |
| `user` | 프로필 등 사용자 계정 정보를 담당합니다. |
| `trip` | 여행을 담당합니다. |
| `expense` | 지출을 담당합니다. |
| `settlement` | 정산을 담당합니다. |

각 영역은 자신의 Entity, UseCase와 Repository를 소유합니다. 업무 간 Repository 접근은 [업무 간 접근](#업무-간-접근)을 따릅니다. 패키지 분리는 별도 서버나 데이터베이스로 분리한다는 의미가 아닙니다.

## 기능별 진입점

애플리케이션 진입점은 사용자 의도별 `*UseCase`로 나눕니다. 각 UseCase가 해당 기능의 실행 흐름과 트랜잭션을 담당합니다. 하나의 업무 Service에 모든 기능을 모으지 않습니다. 조회 기능에는 저장이나 업무 상태 변경을 넣지 않습니다.

같은 업무와 다른 업무 모두에서 UseCase끼리 호출하지 않습니다. 공통 작업은 역할이 명확한 협력 객체로 추출합니다. 협력 객체도 UseCase를 호출하거나 UseCase 타입에 의존하지 않습니다. 여러 작업의 실행 순서와 전체 트랜잭션은 진입점인 UseCase가 담당합니다.

### 업무 내부 구조

여행 업무의 구조 예시입니다.

```text
module/trip/
├── presentation
│   ├── TripController.kt
│   ├── request
│   └── response
├── usecase
│   ├── CreateTripUseCase.kt
│   ├── JoinTripUseCase.kt
│   ├── CloseTripUseCase.kt
│   ├── GetTripDetailUseCase.kt
│   └── SearchTripsUseCase.kt
├── implement
│   ├── TripCreator.kt
│   └── TripParticipantAdder.kt
├── domain
│   ├── Trip.kt
│   ├── TripStatus.kt
│   ├── TripRepository.kt
│   ├── CreateTripCommand.kt
│   ├── SearchTripsQuery.kt
│   ├── TripDetailResult.kt
│   └── TripParticipationPolicy.kt
└── infra
    └── persistence
        ├── TripRepositoryAdapter.kt
        ├── jpa
        │   └── TripJpaRepository.kt
        └── jdsl
            └── TripJdslRepository.kt
```

Controller는 업무별 하나로 제한하지 않습니다. 처음에는 하나로 시작하고, 서로 다른 기능의 의존성과 변경이 한 클래스에 모이면 응집된 기능 단위로 나눕니다. URL 경로와 Controller 클래스의 분리 단위를 일치시킬 필요는 없습니다.

각 Controller는 HTTP 입력과 응답을 변환하고 해당 UseCase를 호출합니다. 생성자 의존성 개수만 줄이기 위해 UseCase를 합치거나 단순 전달용 Facade를 추가하지 않습니다. API 명세의 분류 기준은 [API 문서](api-documentation.md)를 따릅니다.

`implement`와 Policy는 실제 역할이 필요할 때 추가합니다. 구조를 채우기 위한 클래스나 빈 패키지는 만들지 않습니다.

### Repository 경계

업무 Repository는 소유 업무의 `domain` 패키지 바로 아래에 인터페이스로 선언합니다. UseCase와 협력 객체는 이 인터페이스에 의존합니다. 구현 Adapter는 해당 업무의 `infra/persistence`에 두고 기술별 영속성 구현에 위임합니다. Spring Data JPA 인터페이스는 `infra/persistence/jpa`, Kotlin JDSL 쿼리 구현은 `infra/persistence/jdsl`에 둡니다. 사용하는 기술의 패키지만 만듭니다.

```text
UseCase → domain의 Repository 인터페이스
                    ↑ 구현
          infra의 RepositoryAdapter
                    ↓
          JpaRepository 등 영속성 구현
```

Repository 인터페이스는 업무에 필요한 메서드만 선언하며 Spring Data 인터페이스를 상속하지 않습니다. 입력과 반환 타입에 `Pageable`, Spring Data의 `Page`나 영속성 기술 전용 타입을 노출하지 않습니다. 메서드 계약과 구현 기술 선택은 [영속성 가이드](persistence.md#repository-경계와-조회)를 따릅니다.

### 업무 간 접근

다른 업무의 Repository 호출을 허용합니다. 조회 결과는 Entity 대신 필요한 값으로 반환하고, 다른 업무의 Entity를 조회한 뒤 변경 감지로 직접 수정하지 않습니다. 변경은 데이터 소유 업무가 제공하는 변경 의도가 명확한 메서드로 수행합니다.

호출한 UseCase가 선행 업무 검증과 트랜잭션을 책임집니다. 여러 곳에서 반복되는 정책은 Policy로 공유합니다. Repository 호출만으로 검증이나 접근 권한이 보장된다고 판단하지 않습니다.

## 확장과 운영 비용

팀이 구현하고 운영할 수 있는 단순한 구조로 시작합니다. 예상 트래픽이나 미래의 장애 가능성만으로 메시지 브로커, 분산 처리와 별도 저장소를 미리 도입하지 않습니다.

장애, 응답 지연, 처리량과 DB 경합이 요구 수준을 벗어나면 원인과 영향을 확인하고 대안을 검토합니다. 특정 TPS만으로 기술을 도입하지 않고 기능의 요구 수준과 측정 결과를 함께 판단합니다.

새로운 기술이나 외부 시스템은 해결 효과, 운영 난이도와 관리 비용을 비교해 결정합니다. 기능 구현 시 확인되는 실패 경로와 데이터 오류는 해당 기능에서 처리하되, 상세한 외부 호출 순서와 트랜잭션 전략은 기능별로 결정합니다.

## 외부 서비스 연동

업무별 변환이나 실패 처리를 분리해야 하면 Port와 Adapter를 사용합니다. 모든 외부 연동에 인터페이스를 기계적으로 추가하지 않습니다.

```text
UseCase
  → 업무별 Port 인터페이스
  → 업무별 Adapter
  → external 기술 클라이언트
  → 외부 서비스
```

| 구성 요소 | 위치와 책임 |
| --- | --- |
| Port | 사용하는 업무의 `usecase/port`에 두고, 해당 업무에 필요한 최소 기능을 정의합니다. |
| Adapter | 해당 업무의 `infra/client`에서 Port를 구현하고, 업무 입력과 결과를 외부 기술 계약으로 변환합니다. |
| 기술 클라이언트 | `external`에서 외부 API와 SDK, 인증, timeout과 프로토콜 오류를 처리합니다. 업무 판단과 실행 순서는 소유하지 않습니다. |

UseCase는 Port를 호출하고 Adapter는 Port를 구현합니다. `external`은 업무의 Port를 직접 구현하거나 `module`에 의존하지 않습니다. 외부 SDK 타입, 제공자의 응답과 예외를 업무 경계 밖으로 전파하지 않습니다.

재시도는 작업의 멱등성과 외부 부수 효과를 확인하고 적용합니다. 업무별 실패 해석과 호출 순서는 Adapter와 UseCase에서 명시합니다. 같은 제공자를 사용하더라도 업무 의미가 다른 Port와 Adapter를 편의상 합치지 않습니다.

## 이벤트 처리

주요 처리가 끝난 뒤 독립적으로 수행할 후속 작업에만 이벤트를 사용합니다. 반드시 함께 성공해야 하는 변경은 직접 호출과 트랜잭션으로 처리합니다. 이벤트를 UseCase 간 직접 호출 금지 규칙을 우회하는 수단으로 사용하지 않습니다.

### 발행과 소유권

발행 UseCase에서 Spring의 `ApplicationEventPublisher`를 직접 호출합니다. 공통 발행 wrapper나 Entity의 이벤트 발행 기능은 추가하지 않습니다.

이벤트는 발행 업무의 `event` 패키지에 Kotlin `data class`로 선언합니다. `ExpenseCreated`, `TripClosed`처럼 완료된 사실로 이름을 짓고, 수신 처리에 필요한 식별자와 값만 담습니다. JPA Entity나 HTTP DTO를 전달하지 않습니다.

### 수신과 실행

리스너는 수신 업무의 `presentation`에 Controller와 함께 둡니다. `web`, `event` 하위 패키지로 나누지 않고 `Controller`, `Listener` 접미사로 구분합니다.

```text
발행 업무의 UseCase
  → 발행 업무의 event data class
  → 수신 업무의 presentation Listener
  → 수신 업무의 UseCase
```

리스너에는 `@TransactionalEventListener(phase = AFTER_COMMIT)`과 `@Async("eventExecutor")`를 적용합니다. 발행 트랜잭션의 커밋 성공 후 별도 스레드에서 실행하며, 롤백되면 후속 처리를 실행하지 않습니다. 트랜잭션 없이 발행한 이벤트를 처리하기 위해 `fallbackExecution`을 활성화하지 않습니다.

리스너는 이벤트를 입력값으로 변환해 UseCase를 호출합니다. 업무 판단과 트랜잭션은 수신 UseCase가 담당합니다. 발행 트랜잭션은 비동기 실행 스레드로 전파되지 않습니다.

비동기 활성화, 스레드 풀 크기와 작업 거절은 [비동기 이벤트 처리 설정](../operations/configuration.md#비동기-이벤트-처리)을 따릅니다. 리스너는 `Unit`을 반환하며 결과를 기다리는 Future 계약으로 사용하지 않습니다.

### 실패 처리

자동 재시도는 기본으로 적용하지 않습니다. 비동기 처리 실패 시 이벤트 식별자와 원인 예외를 [로깅 기준](../operations/logging.md)에 따라 한 번 기록합니다. 리스너가 이벤트 식별자와 예외를 기록했다면 다시 던지지 않습니다. 처리하지 않은 예외는 공통 비동기 예외 처리기가 리스너와 메서드 이름, 원인 예외를 기록합니다. 이벤트 객체 전체나 민감정보는 기록하지 않습니다. 재시도와 중복 방지가 필요한 작업은 해당 기능에서 결정합니다.

프로세스 내부 이벤트는 영구 저장이나 전달을 보장하지 않습니다. 서버 종료로 후속 처리가 유실될 수 있으므로 유실을 허용할 수 없는 작업에는 이 방식만으로 완료를 보장하지 않습니다. 추가 인프라 도입은 [확장과 운영 비용](#확장과-운영-비용) 기준을 따릅니다.

## 업무 규칙

| 규칙의 책임 | 위치 |
| --- | --- |
| 업무 판단, 검증과 실행 순서 | UseCase |
| 데이터, JPA 매핑과 단순 상태 변경 | Entity |
| 여러 곳에서 반복되는 동일한 업무 판단과 검증 | Policy |

JPA Entity와 별도의 도메인 모델을 이중 관리하지 않습니다. Entity는 데이터와 매핑 중심으로 두고, `changeName()`, `close()` 같은 단순 상태 변경 메서드를 허용합니다. 변경 가능 여부와 업무 검증은 UseCase에서 판단하며 Entity에 넣지 않습니다.

동일한 정책이나 검증이 여러 곳에서 반복되면 Policy로 추출합니다. 조건이 많거나 여러 객체를 사용한다는 이유만으로 Policy를 미리 만들지 않습니다. Policy는 소유 업무의 `domain` 패키지 바로 아래에 별도 파일로 두고, `policy` 하위 패키지는 만들지 않습니다.

Policy는 판단에 필요한 객체와 값을 입력받습니다. DB 조회와 외부 호출은 UseCase나 협력 객체가 수행하고, Policy에 Repository나 외부 클라이언트를 주입하지 않습니다. 시간이 필요한 판단에는 기준 시각을 명시적으로 전달합니다.

같은 규칙을 여러 위치에 복제하지 않습니다. DB 제약조건과 동시성 방어의 역할은 [영속성 가이드](persistence.md#동시성과-제약조건)를 따릅니다.

### Enum 소유권과 참조

업무 enum은 소유 업무의 `domain`에 둡니다. 여러 업무에서 사용한다는 이유로 `global`로 옮기지 않습니다.

다른 업무는 enum을 직접 참조해 비교하거나 분기할 수 있습니다. 이름이 같아도 의미와 변경 기준이 다르면 별도로 정의합니다.

상태 enum은 값과 고정 속성만 표현합니다. 업무 판단, 검증과 상태 전이 로직은 넣지 않습니다.

상태를 사용하는 업무의 UseCase가 판단을 담당합니다. 반복되는 동일한 정책은 Policy로 추출합니다.

상태 변경은 데이터 소유 업무가 담당합니다. 다른 업무의 enum 참조가 Entity의 직접 변경을 허용하지는 않습니다.

enum을 변경할 때 사용하는 업무의 분기, 조회와 갱신 쿼리, API 계약을 함께 확인합니다.

## 협력 객체

업무 로직은 먼저 UseCase에 작성합니다. 주요 흐름을 읽기 어려워지거나 같은 작업이 반복되면 역할별 협력 객체로 추출합니다. 협력 객체는 해당 업무의 `implement` 패키지에 둡니다. 단순 조회와 저장을 전달하기 위한 계층은 만들지 않습니다.

### 이름

협력 객체는 대상과 실제 동작을 조합해 이름을 짓습니다. 접미사는 `Creator`, `Updater`, `Deleter`, `Reader`, `Validator`, `Adder`, `Remover`, `Calculator`처럼 익숙한 동작 표현을 사용합니다.

| 역할 | 이름 예시 |
| --- | --- |
| 여행 생성 | `TripCreator` |
| 참여자 추가 | `TripParticipantAdder` |
| 참여자 제외 | `TripParticipantRemover` |
| 참여 조건 검증 | `TripParticipationValidator` |
| 정산 금액 계산 | `SettlementCalculator` |

`Registrar`처럼 의미를 해석해야 하는 접미사 대신 수행하는 동작을 직접 표현합니다. `Manager`, `Helper`, `Processor`처럼 책임 범위가 넓은 이름은 사용하지 않습니다. 이름을 맞추기 위해 객체를 만들거나 하나의 작업을 형식적으로 나누지 않습니다.
