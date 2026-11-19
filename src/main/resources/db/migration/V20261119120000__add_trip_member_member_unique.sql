/** uq_trip_member_trip_id_member_id = 같은 회원은 한 여행에 한 번만 참여 | member_id가 NULL인 비로그인 참여자는 제약 대상이 아님 **/
ALTER TABLE trip_member
    ADD CONSTRAINT uq_trip_member_trip_id_member_id UNIQUE (trip_id, member_id);
