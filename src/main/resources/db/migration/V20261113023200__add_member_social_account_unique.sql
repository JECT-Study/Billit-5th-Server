/** 같은 제공자의 외부 사용자 ID로 회원을 한 번만 생성 | social_provider_user_id가 NULL인 행(탈퇴로 삭제, 미기록)끼리는 중복 허용 **/
ALTER TABLE member
    ADD CONSTRAINT uq_member_social_provider_social_provider_user_id UNIQUE (social_provider, social_provider_user_id);
