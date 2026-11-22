CREATE TYPE trip_status AS ENUM (
    'UNCONFIRMED', /** UNCONFIRMED = 확정 전 (참여자 초대 중) **/
    'CONFIRMED' /** CONFIRMED = 확정 후 (여행 중) **/
);

/** trip_member_role | OWNER → ADMIN | ADMIN = 관리자 (여행 생성자, 여행당 1명) | MEMBER = 참여자 **/
ALTER TYPE trip_member_role RENAME VALUE 'OWNER' TO 'ADMIN';

ALTER TABLE trip
    ALTER COLUMN country_code TYPE VARCHAR(3), /** 대표 국가 코드 | KR, JP, US, CN, EU | ETC = 기타 국가 → 국가명은 custom_country_name **/
    ADD COLUMN custom_country_name VARCHAR(10), /** 기타 국가로 직접 입력한 국가명 | NULL = country_code가 ETC가 아님 **/
    ADD COLUMN idempotency_key UUID, /** 앱에서 발급한 여행 생성 고유키 | 여행 생성에만 사용 | 만료 없이 여행과 함께 보관 **/
    ADD COLUMN status trip_status /** 여행 확정 상태 **/;

/** 기존 행 보정 | 고유키가 없던 행에는 임의 키를 채움 | 확정 상태는 members_locked_at 유무로 결정 **/
UPDATE trip
SET idempotency_key = gen_random_uuid(),
    status = CASE
        WHEN members_locked_at IS NULL THEN 'UNCONFIRMED'::trip_status
        ELSE 'CONFIRMED'::trip_status
    END;

ALTER TABLE trip
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN status SET NOT NULL;

/** confirmed_at = 여행 확정 시각 | NULL = 확정 전 | 확정 여부 판정 → status | 확정 후 참여자와 정원 수정 불가 **/
ALTER TABLE trip
    RENAME COLUMN members_locked_at TO confirmed_at;

/** uq_trip_idempotency_key = 같은 고유키로 여행을 한 번만 생성 | member_capacity = 생성자 포함 총 정원 1~10명 **/
ALTER TABLE trip
    ADD CONSTRAINT uq_trip_idempotency_key UNIQUE (idempotency_key),
    ADD CONSTRAINT ck_trip_valid_date_range CHECK (end_date >= start_date),
    ADD CONSTRAINT ck_trip_member_capacity_range CHECK (member_capacity BETWEEN 1 AND 10);

/**
 * trip_member 컬럼의 의미와 이름 변경
 * trip_member = 여행 참여자 | 로그인 참여자가 참여하거나 관리자가 비로그인 참여자를 추가할 때 행 생성 | 빈자리 행은 만들지 않음 | 빈자리 수 = trip.member_capacity - 등록된 참여자 수
 * id = 여행 참여자 ID
 * member_id = 회원 ID → member.id | NULL = 비로그인 참여자
 * trip_nickname = 여행방 닉네임 | 로그인 참여자와 비로그인 참여자 모두 저장 | 같은 여행 안에서 중복 불가 | 여행 확정 전과 후 모두 수정 가능
 * joined_at = 초대 코드 참여 시각 | NULL = 초대 코드로 참여하지 않음 (여행 생성자, 비로그인 참여자)
 * created_at = 참여자 등록 시각
 **/
ALTER TABLE trip_member
    RENAME COLUMN guest_name TO trip_nickname;

ALTER TABLE trip_member
    RENAME CONSTRAINT uq_trip_member_trip_id_guest_name TO uq_trip_member_trip_id_trip_nickname;

/** 여행당 관리자(ADMIN)는 1명 **/
CREATE UNIQUE INDEX uq_trip_member_trip_id_admin
    ON trip_member (trip_id)
    WHERE role = 'ADMIN';
