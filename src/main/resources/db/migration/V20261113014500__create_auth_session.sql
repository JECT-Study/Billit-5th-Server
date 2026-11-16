CREATE TABLE auth_session (
    id UUID NOT NULL, /** 세션 ID | 회원 토큰의 sid 클레임 값 **/
    member_id BIGINT NOT NULL, /** 회원 ID → member.id **/
    refresh_token_hash VARCHAR(64) NOT NULL, /** 현재 Refresh Token의 SHA-256 해시, 소문자 16진수 | 토큰 원문은 저장하지 않음 **/
    refresh_token_expires_at TIMESTAMPTZ NOT NULL, /** 현재 Refresh Token 만료 시각 **/
    previous_refresh_token_hash VARCHAR(64), /** 직전 Refresh Token의 SHA-256 해시, 소문자 16진수 | NULL = 회전 이력 없음 | 갱신 응답을 받지 못한 클라이언트의 재시도 확인용 **/
    refresh_token_rotated_at TIMESTAMPTZ, /** 마지막 Refresh Token 회전 시각 | NULL = 회전 이력 없음 **/
    revoked_at TIMESTAMPTZ, /** 세션 종료 시각 | NULL = 유효한 세션 **/
    version BIGINT NOT NULL, /** 낙관적 잠금 버전 | 동시 회전과 종료 충돌 감지 **/
    created_at TIMESTAMPTZ NOT NULL, /** 세션 생성 시각 **/
    updated_at TIMESTAMPTZ NOT NULL, /** 최종 수정 시각 **/
    CONSTRAINT pk_auth_session PRIMARY KEY (id)
);

ALTER TABLE auth_session
    ADD CONSTRAINT fk_auth_session_member_id
    FOREIGN KEY (member_id) REFERENCES member (id) ON UPDATE NO ACTION ON DELETE NO ACTION;

CREATE INDEX idx_auth_session_member_id
    ON auth_session (member_id);
