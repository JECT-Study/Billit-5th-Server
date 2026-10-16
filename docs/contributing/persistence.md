# 영속성 가이드

## 기술과 책임

DB는 PostgreSQL, 영속성 기술은 Spring Data JPA를 사용합니다. DB 접근과 매핑은 해당 업무의 Repository와 영속성 코드가 담당합니다. 여러 Repository 호출의 실행 순서와 트랜잭션 경계는 유스케이스가 소유합니다.

JPA의 변경 감지와 지연 로딩을 트랜잭션 경계 안에서 다룹니다. HTTP DTO에 Entity를 직접 노출하거나 응답 직렬화 과정에서 지연 로딩에 의존하지 않습니다. 조회 결과는 API 계약에 필요한 값으로 변환합니다.

## 스키마와 Entity 매핑

JPA의 자동 스키마 생성과 변경은 사용하지 않습니다. 모든 프로필과 DB 통합 테스트에서 `spring.jpa.hibernate.ddl-auto: validate`를 유지합니다. 스키마 정의의 기준은 `src/main/resources/db/migration`의 Flyway SQL입니다. Entity 매핑은 해당 스키마와 일치시킵니다.

Entity annotation은 ORM 매핑과 실행에 필요한 설정을 표현합니다. DDL 생성만을 위한 속성으로 DB 스키마를 중복 정의하지 않습니다.

| 구분 | 기준 |
| --- | --- |
| 매핑과 실행에 필요한 설정 | 테이블과 컬럼 이름, 식별자 생성 전략, 관계 매핑, 값 변환, `@Version`, `insertable`, `updatable` 등을 선언합니다. |
| DDL 생성 전용 설정 | 유니크 제약, 인덱스, DDL용 FK 이름과 `columnDefinition`은 스키마 정의에서 관리합니다. |
| 매핑과 실행에도 영향을 주는 설정 | nullability, 관계의 `optional`, JDBC 타입은 실행에 필요한 이유를 확인하고 DB 정의와 일치시킵니다. |

유니크 제약은 DB에 정의하고, Entity에는 매핑에 필요한 속성만 선언합니다.

컬럼 매핑 예시입니다.

```kotlin
@Column(name = "email")
val email: String
```

`@JoinColumn(name = "account_id")`처럼 관계 매핑에 필요한 설정은 유지합니다. 식별자 생성 전략처럼 DB 정의와 ORM 실행 양쪽에 필요한 설정은 중복 금지 대상이 아닙니다.

`validate`가 모든 제약과 인덱스를 검증한다고 가정하지 않습니다. 실제 PostgreSQL에서 제약 위반과 업무 오류 변환을 검증합니다. 시각 타입과 저장 형식은 [시간 처리](../operations/time.md)를 따릅니다.

## Flyway migration

### 파일과 버전

SQL은 `src/main/resources/db/migration`에서 관리합니다. 파일명은 UTC 기준 `VyyyyMMddHHmmss__설명.sql`을 사용하며 설명은 영문 snake_case로 작성합니다.

```text
V20261015060000__create_trip.sql
V20261015060100__add_trip_status.sql
```

같은 버전을 중복 사용하지 않습니다. 이미 병합한 migration은 수정, 삭제하거나 이름을 바꾸지 않습니다. 변경은 새 migration으로 추가합니다. 최초 prod 배포 시에도 개발 중 쌓인 이력을 유지하고 하나의 초기 파일로 통합하지 않습니다. 서비스 릴리스 버전과 migration 버전은 별도로 관리합니다.

아직 병합하지 않았고 공유 환경에도 적용하지 않은 파일이 최신 migration보다 낮은 버전이면 최신 버전 뒤로 조정합니다. 이미 공유 환경에 적용했다면 파일명을 바꾸거나 이력을 강제로 수정하지 말고 적용 이력을 확인해 전환 방식을 합의합니다.

### 실행

`local`, `test`, `dev`, `prod` 모두 애플리케이션 기동 시 Flyway를 실행하고, Hibernate는 migration 적용 후 스키마를 검증합니다. Testcontainers도 운영과 같은 migration을 사용하며 테스트용 자동 DDL로 대체하지 않습니다.

- `validate-on-migrate: true`로 적용 이력과 체크섬을 검증합니다.
- `validate-migration-naming: true`로 잘못된 파일명을 거절합니다.
- `out-of-order: false`로 적용된 최신 버전보다 낮은 migration의 뒤늦은 실행을 허용하지 않습니다.
- `baseline-on-migrate: false`를 유지합니다. 기존 테이블이 있는 DB에 자동으로 기준 이력을 만들지 않습니다.
- `clean-disabled: true`를 유지합니다. 오류 해결을 위해 공유 DB를 초기화하거나 `repair`로 불일치를 무조건 덮지 않습니다.

Flyway 이력이 없는 기존 DB에 적용할 때는 스키마, 보존 데이터와 기준 버전을 확인하고 별도 전환 절차를 합의합니다.

### 병렬 작업

타임스탬프는 버전 중복을 줄이는 수단이며 병합 순서와 SQL 충돌을 보장하지 않습니다. PR은 최신 대상 브랜치를 합친 결과로 검증하고, 대상 브랜치가 바뀌면 다시 검증합니다.

## Base Entity

생성, 수정, 삭제 시각을 공통으로 관리하는 Entity는 `@MappedSuperclass`로 선언한 추상 `BaseEntity`를 상속합니다. BaseEntity는 공통 필드와 생성, 수정 시각의 자동 기록을 담당합니다. 생성, 수정, 삭제의 업무 규칙과 상태 변경은 개별 Entity와 유스케이스에서 명시합니다. 공통 필드 전체가 필요하지 않은 Entity에 상속을 강제하지 않습니다.

| 프로퍼티 | 컬럼 | 기준 |
| --- | --- | --- |
| `createdAt` | `created_at` | 최초 저장 시 기록하고 이후 변경하지 않습니다. |
| `updatedAt` | `updated_at` | 최초 저장 시 생성 시각과 같은 값으로 기록하고 Entity 수정 시 갱신합니다. |
| `deletedAt` | `deleted_at` | 기본값은 `null`이며 업무에서 삭제할 때 삭제 시각을 명시적으로 기록합니다. |

세 시각은 `Instant`로 선언합니다. 타입과 DB 저장 형식은 [시간 처리](../operations/time.md)를 따릅니다. 외부 코드가 시각을 임의로 변경하지 못하도록 setter를 제한합니다. 저장 전 미설정 상태를 표현하려면 nullable 타입을 사용하고 임의의 과거 시각을 기본값으로 넣지 않습니다. 상속 Entity의 테이블에는 Flyway migration으로 공통 컬럼을 정의하며, `deleted_at`은 nullable `TIMESTAMPTZ`로 정의합니다.

식별자와 생성 전략은 개별 Entity에서 정의합니다. 별도의 삭제 여부 플래그, 업무 상태와 `@Version`은 해당 업무의 요구사항에 따라 선언하고 Base Entity에 일괄 추가하지 않습니다. `equals`, `hashCode`와 `toString`도 공통 필드만으로 일괄 구현하지 않습니다.

### 삭제와 조회

Entity의 삭제 여부는 `BaseEntity.isDeleted()`로 확인합니다. `deletedAt`이 `null`이 아니면 `true`를 반환합니다. 공통 필드에서 계산하는 상태 확인 메서드는 BaseEntity에 두고, 상태 변경이나 업무별 판단은 포함하지 않습니다.

논리 삭제는 개별 Entity의 `delete(deletedAt: Instant)`처럼 의도를 드러내는 업무 메서드에서 `deletedAt`을 설정합니다. 유스케이스는 주입받은 `Clock`에서 시각을 구해 전달합니다. 삭제 가능 조건, 중복 삭제와 복구 정책은 해당 업무에서 정의하며 BaseEntity에 공통 삭제 동작을 넣지 않습니다.

`@SQLDelete`, `@SQLRestriction`, `@Where`, `@SoftDelete`나 전역 필터로 삭제 동작과 조회 조건을 암묵적으로 적용하지 않습니다. 논리 삭제를 `Repository.delete()`나 `deleteById()`로 표현하지 않습니다. 물리 삭제는 해당 업무에서 명시적으로 허용한 경우에만 실행합니다.

삭제되지 않은 데이터의 조회는 `findByIdAndDeletedAtIsNull(id)`, 존재 확인은 `existsByIdAndDeletedAtIsNull(id)`처럼 Spring Data JPA 메서드 이름에 조건을 드러냅니다. 삭제된 데이터는 `DeletedAtIsNotNull`로 표현합니다. 직접 작성한 JPQL과 SQL에도 같은 조건을 명시합니다. 삭제 여부를 제한하지 않는 `findById()` 등의 조회는 삭제된 데이터를 포함하는 업무에서만 사용합니다.

### 시각 기록

시각은 Spring Data JPA Auditing의 `@CreatedDate`, `@LastModifiedDate`와 `AuditingEntityListener`로 기록합니다. `DateTimeProvider`는 주입받은 `Clock`에서 시각을 구합니다. Auditing 활성화와 provider Bean은 설정 클래스에 두며 메인 클래스에 추가하지 않습니다. API와 설정 방식은 [Spring Data JPA Auditing](https://docs.spring.io/spring-data/jpa/reference/auditing.html)을 따릅니다.

같은 필드에 Auditing, Hibernate 시각 생성 annotation과 DB 자동 갱신을 중복 적용하지 않습니다. JPQL 벌크 UPDATE나 직접 실행한 SQL은 Entity의 Auditing 경로를 거치지 않으므로 수정 시각도 해당 쿼리에서 함께 변경합니다.

### 검증

Auditing annotation이나 Bean 등록 자체를 검사하는 별도 테스트는 추가하지 않습니다. 업무 통합 테스트에서 시각이 결과에 영향을 주면 고정된 `Clock`을 사용하고 저장 후 다시 조회해 검증합니다. DB 시각 정밀도를 반영하며, 수정 후에도 생성 시각이 유지되는지 확인합니다. 벌크 변경은 수정 시각의 반영 여부를 함께 확인합니다.

## Repository 경계와 조회

- Repository는 해당 업무가 소유하는 저장과 조회를 담당합니다. Command, Query 구분과 업무 간 접근은 [아키텍처](architecture.md#command와-query)를 따릅니다.
- 조회 입력, 업무 결과와 HTTP DTO의 구분은 [코드 스타일](code-style.md#입력과-반환-계약)을 따릅니다.
- 영속성 구현의 쿼리 객체나 provider 전용 타입을 유스케이스와 HTTP 계층으로 전파하지 않습니다.
- 테스트 편의만을 위한 Repository 인터페이스나 범용 Repository를 추가하지 않습니다.
- 단일 Repository 호출이 트랜잭션을 사용한다는 이유로 여러 호출의 원자성을 보장한다고 판단하지 않습니다.

조회는 필요한 데이터와 연관관계의 접근 범위를 확인해 작성합니다. 연관관계를 순회하는 코드에서는 실제 SQL과 추가 조회 횟수를 확인합니다. 복잡한 조회를 ORM 관계 탐색으로 숨기지 않습니다. 별도 조회 기술을 도입할 때는 쿼리 복잡성, 매핑과 트랜잭션 참여 방식을 검토합니다.

### 메서드 계약

변경 메서드는 `closeIfOpen()`, `addParticipant()`처럼 대상과 변경 의도를 드러냅니다. `update()`, `changeStatus()`처럼 범위가 불분명한 변경 계약은 다른 업무에 공개하지 않습니다.

다른 업무에서 호출하는 변경 메서드의 KDoc에는 변경 대상, 호출 전 필요한 업무 검증, 변경 조건, 반환값과 실패 결과, 트랜잭션 요구사항을 명시합니다. 조건을 만족하지 않은 결과와 DB 오류를 구분합니다.

## 트랜잭션

`@Transactional`은 여러 작업을 하나의 업무 단위로 실행하는 경계에 둡니다. Spring 프록시를 거치지 않는 자기 호출이나 생성자로 직접 만든 객체의 호출로 트랜잭션을 검증하지 않습니다.

별도 트랜잭션 매니저, DataSource나 스레드를 사용하면 기존 트랜잭션에 참여하는지 확인합니다. 자동 설정 충돌을 추측해 매니저를 추가하거나 자동 설정을 제외하지 않습니다. 제외 기준은 [환경 설정](../operations/configuration.md#자동-설정-스케줄러와-필터)을 따릅니다.

JPA는 SQL 실행과 제약 위반이 flush나 commit 시점에 발생할 수 있습니다. 오류 변환과 후속 작업은 실제 실패 시점을 고려해 배치합니다. Repository 메서드가 반환됐다는 이유만으로 DB 반영이 완료됐다고 판단하지 않습니다.

DB 롤백이 외부 API 호출이나 다른 저장소의 변경까지 되돌리지는 않습니다. 트랜잭션 완료 전후의 부수 효과와 실패 결과를 구분합니다. 외부 부수 효과가 있는 유스케이스 전체를 멱등성 검토 없이 자동 재시도하지 않습니다.

## 동시성과 제약조건

사전 조회는 경쟁 요청 사이의 중복이나 상태 변경을 막지 못합니다. 업무 규칙에 따라 DB 제약조건, 조건부 UPDATE, 낙관적 잠금이나 비관적 잠금으로 변경을 보호합니다. 적용 방식은 충돌 시 업무 결과와 재시도 조건을 확인해 결정합니다.

조건부 UPDATE가 업무 상태를 제한하면 조회 후 무조건 변경하는 코드로 대체하지 않습니다. 변경된 행 수와 실패 결과를 함께 처리합니다. 도메인 코드와 SQL 조건에 같은 규칙이 있으면 동일한 경계값과 상태 조합을 검증합니다.

## 오류 변환

DB 오류는 해당 업무에서 정의한 실패에 대응할 때만 변환합니다. 모든 무결성 오류를 중복으로 처리하거나 원인을 확인하지 않은 채 정상 결과로 바꾸지 않습니다.

제약 위반을 변환할 때는 원인 예외, SQLSTATE와 제약 이름 등 식별 근거를 확인합니다. 예를 들어 PostgreSQL의 `23505`는 유니크 제약 위반을 나타내지만, 어떤 업무의 중복인지는 해당 제약을 확인해야 합니다. 다른 SQL 오류는 원래 실패를 유지합니다.

## 검증

검증 범위와 Testcontainers 사용은 [테스트 가이드](testing.md)를 따릅니다.

- 스키마와 Entity의 타입, nullability와 식별자 생성 전략이 일치하는지 확인합니다.
- 식별자 반환, nullable 값과 시각의 저장 후 조회 결과를 확인합니다.
- 여러 변경이 함께 커밋되거나 실패 시 롤백되는지 실제 DB에서 확인합니다.
- 제약 위반이 정해진 업무 실패로 변환되는지 확인합니다.
- 동시 INSERT와 상태 변경에서 제약조건이나 잠금이 업무 규칙을 보장하는지 확인합니다.
- 외부 연동 실패와 DB 커밋 실패 후 DB 상태와 부수 효과를 각각 확인합니다.
- 조회 시 실행되는 SQL과 연관관계 접근으로 인한 추가 조회를 확인합니다.
