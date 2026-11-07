# DB 네이밍 가이드

PostgreSQL 테이블, 컬럼, enum, 제약과 인덱스 이름에 적용합니다.

## 기본 형식

- DB 식별자는 소문자 `snake_case`로 작성합니다.
- 테이블 이름은 단수 명사로 작성합니다.
- 큰따옴표가 필요한 대소문자 구분 이름과 예약어를 피합니다.
- PostgreSQL 기본 식별자 한도인 63바이트 안에서 이름을 정합니다. 자동 잘림에 의존하지 않습니다.
- 의미를 알기 어려운 약어와 `tbl_`, `col_` 같은 형식 접두사를 사용하지 않습니다. `id`, `krw`, `ocr`처럼 도메인에서 합의한 약어는 사용합니다.

## 테이블과 도메인 용어

| 대상 | 이름 | 구분 |
| --- | --- | --- |
| 서비스 회원 | `member` | 소셜 로그인 계정과 프로필을 관리합니다. |
| 여행 | `trip` | 여행별 참여자와 지출을 관리합니다. |
| 여행 참여자 자리 | `trip_member` | 회원, 비로그인 참여자와 빈자리를 표현합니다. |
| 지출 | `expense` | 결제한 지출을 기록합니다. |
| 지출 항목 | `expense_item` | 지출 안의 개별 항목입니다. |
| 항목별 부담 참여자 연결 | `expense_item_member` | 항목과 `trip_member`를 연결합니다. |
| 개인 저장 환율 | `manual_exchange_rate` | 지출에 복사된 적용 환율과 구분합니다. |
| 정산 | `settlement` | 정산 준비와 발행 결과를 관리합니다. |
| 정산 대상 지출 연결 | `settlement_expense` | 정산과 지출을 연결합니다. |
| 송금 경로 | `transfer` | 정산 결과의 송금인과 수령인을 연결합니다. |
| 회원별 불편 사항 응답 | `member_pain_point` | 서비스 회원의 설문 응답을 기록합니다. |
| 탈퇴 사유 | `withdrawal_reason` | 탈퇴 사유 기록을 표현합니다. |

연결 테이블은 연결하는 도메인 이름으로 작성합니다. 역할과 저장 데이터를 구분할 수 없을 때만 업무 의미를 추가합니다.

## 식별자와 참조

| 대상 | 형식 | 예시 |
| --- | --- | --- |
| 기본키 | `id` | `member.id`, `trip_member.id` |
| 외래키 | `<참조 대상>_id` | `member_id`, `trip_id`, `expense_id` |
| 역할이 있는 외래키 | `<역할>_<참조 대상>_id` | `payer_trip_member_id`, `receiver_trip_member_id` |
| 생성자 외래키 | `created_by_<참조 대상>_id` | `created_by_trip_member_id` |
| 외부 제공자 식별자 | `<제공자 구분>_<외부 식별자>` | `social_provider_user_id` |

`member_id`는 서비스 회원, `trip_member_id`는 여행 참여자 자리를 참조합니다. 두 식별자를 혼용하지 않습니다. 외부 소셜 제공자의 사용자 ID에 포함된 `user`는 내부 회원 테이블명과 별개로 유지합니다.

## 컬럼의 의미와 접미사

| 의미 | 형식 | 예시 |
| --- | --- | --- |
| 달력 날짜 | `_date` | `start_date`, `end_date`, `paid_date` |
| 사건 발생 시각 | `_at` | `created_at`, `received_at`, `withdrawn_at` |
| 만료 시각 | `_expires_at` | `edit_lock_expires_at` |
| 실제 개수 | `_count` | 집계한 개수에 사용합니다. |
| 정원 | `_capacity` | `member_capacity` |
| 금액 | `_amount` 또는 `amount` | `original_amount`, `discount_amount`, `krw_amount` |
| 환율 | `exchange_rate` 또는 문맥상 명확한 `rate` | `expense.exchange_rate`, `manual_exchange_rate.rate` |
| 통화 | `currency`, `<역할>_currency` | `currency`, `base_currency`, `settlement_currency` |
| 참과 거짓 | `is_`, `has_` | `is_treasurer` |
| 상태 | `status` 또는 `<대상>_status` | `settlement.status` |
| 분류와 방식 | `_type`, `_method` | `split_type`, `payment_method` |
| 순서 | `sort_order` | `expense_item.sort_order` |
| 기타 직접 입력 | `other_reason_text` | 설문과 탈퇴 사유의 기타 내용 |

현재 값의 개수와 최대 수용 인원을 구분합니다. 총 정원에 `_count`를 사용하지 않습니다.

잠금 이름에는 대상과 역할을 포함합니다. 수정 잠금 소유자는 `edit_lock_owner_trip_member_id`, 수정 잠금 만료는 `edit_lock_expires_at`, 정산 준비 잠금 만료는 `preparation_lock_expires_at`으로 표현합니다.

금액의 저장 단위와 환율 방향은 이름만으로 판단하지 않습니다. 원화 금액과 원본 통화의 최소 단위, 환산 방향을 SQL 주석에 명시합니다.

## NULL과 SQL 주석

컬럼 이름은 저장하는 값의 의미를 표현하고, NULL의 업무 의미는 해당 컬럼 옆에 `/** ... **/` 주석으로 작성합니다. `COMMENT ON`으로 대체하지 않습니다.

```sql
member_id BIGINT, /** 회원 ID → member.id | NULL + guest_name 있음 = 비로그인 | 둘 다 NULL = 빈자리 **/
settlement_currency CHAR(3), /** KRW_UNIFIED → KRW | BY_CURRENCY → NULL = 단일 통화 통합 없음 **/
received_at TIMESTAMPTZ, /** 수령 확인 시각 | NULL = 확인 기록 없음 | 정산 상태 판정 → settlement.status **/
```

enum 값도 `CASH = 현금`처럼 기호 중심으로 설명합니다. 이름을 변경하면서 타입, NULL 허용 여부나 기존 정책을 변경하지 않습니다.

## enum 이름

- PostgreSQL enum 타입 이름은 소문자 `snake_case`, 값은 `UPPER_SNAKE_CASE`로 작성합니다.
- 상태는 `<대상>_status`, 역할은 `<대상>_role`로 구분합니다.
- 회원 상태는 `member_status`, 여행별 권한은 `trip_member_role`로 구분합니다.
- 테이블과 enum 타입에 같은 이름을 사용하지 않습니다. PostgreSQL은 테이블과 같은 이름의 행 타입을 생성합니다.

```sql
CREATE TYPE settlement_status AS ENUM (
    'DRAFT', /** DRAFT = 정산 준비 **/
    'IN_PROGRESS', /** IN_PROGRESS = 발행 후 수령 확인 진행 **/
    'COMPLETED', /** COMPLETED = 정산 완료 **/
    'CANCELED' /** CANCELED = 정산 취소 **/
);
```

탈퇴 사유 테이블은 `withdrawal_reason`, 해당 enum 타입은 `withdrawal_reason_type`으로 구분합니다. 네이밍 규칙만으로 PostgreSQL enum 사용을 모든 필드에 강제하지 않습니다.

## 제약과 인덱스 이름

제약과 인덱스는 다음 형식으로 명명합니다. 예시의 제약과 인덱스 생성을 강제하지 않습니다.

| 대상 | 형식 | 예시 |
| --- | --- | --- |
| PK | `pk_<테이블>` | `pk_member` |
| FK | `fk_<테이블>_<컬럼>` | `fk_trip_member_member_id` |
| UNIQUE | `uq_<테이블>_<컬럼>` | `uq_member_nickname` |
| CHECK | `ck_<테이블>_<검증 의미>` | `ck_trip_valid_date_range` |
| 일반 인덱스 | `idx_<테이블>_<컬럼>` | `idx_expense_trip_id` |

복합 키와 인덱스는 정의한 컬럼 순서대로 이름을 작성합니다. 부분 인덱스는 구분에 필요한 조건의 의미를 추가합니다. 이름이 63바이트를 넘으면 의미가 유지되는 축약 이름을 정하고 중복 여부를 확인합니다.

## Kotlin과 JPA 매핑

Kotlin 식별자의 형식은 [코드 스타일](code-style.md#이름과-타입)을 따릅니다. DB의 `snake_case` 이름을 매핑에 명시합니다.

프로퍼티 매핑 예시입니다.

```kotlin
@field:Column(name = "social_provider_user_id")
val socialProviderUserId: String?

@field:Column(name = "edit_lock_expires_at")
val editLockExpiresAt: Instant?
```

관계를 Entity로 매핑할 때는 `@JoinColumn(name = "member_id")`처럼 실제 FK 컬럼을 명시합니다. 매핑과 DDL의 책임은 [스키마와 Entity 매핑](persistence.md#스키마와-entity-매핑), 시각과 날짜의 타입은 [시간 처리](../operations/time.md)를 따릅니다.

## 이름 변경

- 이름 변경 시 관련 FK, PK, UQ와 인덱스 이름, 쿼리, Entity 매핑과 SQL 주석의 참조도 함께 맞춥니다.
- 기존 컬럼의 타입, NULL 허용 여부, 기본값과 업무 의미는 이름 변경과 구분해서 검토합니다.
- DB 이름 변경을 이유로 API 계약을 자동 변경하지 않습니다.
- migration 파일과 적용 절차는 [Flyway migration](persistence.md#flyway-migration)을 따릅니다.
- 스키마와 매핑 검증은 [영속성 검증](persistence.md#검증)을 따릅니다.
