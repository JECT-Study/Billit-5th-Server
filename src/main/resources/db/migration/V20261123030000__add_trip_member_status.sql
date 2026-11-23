CREATE TYPE trip_member_status AS ENUM (
    'MEMBER', /** MEMBER = 참여 중인 로그인 참여자 **/
    'GUEST', /** GUEST = 비로그인 참여자 **/
    'LEFT', /** LEFT = 나간 회원 | 나간 시각 → left_at **/
    'WITHDRAWN' /** WITHDRAWN = 탈퇴 회원 **/
);

ALTER TABLE trip_member
    ADD COLUMN status trip_member_status, /** 참여자 상태 | 등록 인원 = MEMBER, GUEST 수 | LEFT, WITHDRAWN은 지출과 정산에서 계속 참조 **/
    ADD COLUMN left_at TIMESTAMPTZ /** 여행을 나간 시각 | NULL = 나가지 않음 | 나갔는지는 status = LEFT로 판단 **/;

/** 기존 행 보정 | 나가기와 탈퇴 기능 전이므로 member_id 유무로 결정 **/
UPDATE trip_member
SET status = CASE
    WHEN member_id IS NOT NULL THEN 'MEMBER'::trip_member_status
    ELSE 'GUEST'::trip_member_status
END;

ALTER TABLE trip_member
    ALTER COLUMN status SET NOT NULL;
