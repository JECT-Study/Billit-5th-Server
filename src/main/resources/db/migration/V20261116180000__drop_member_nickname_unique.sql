-- 계정 닉네임은 다른 회원과 겹쳐도 되므로 유니크 제약을 없앱니다.
ALTER TABLE member DROP CONSTRAINT uq_member_nickname;
