package com.ject.module.trip.domain

/**
 * 같은 여행의 다른 참여자가 이미 쓰는 여행방 닉네임으로 참여자를 저장하려 했음을 알립니다.
 *
 * `uq_trip_member_trip_id_guest_name` 위반에서 만듭니다. 제약 위반이 난 트랜잭션은 더 쓸 수 없으므로 롤백되도록 트랜잭션 밖으로 전파합니다.
 * 여행 참여와 비로그인 참여자 등록이 같은 제약을 쓰지만 응답할 오류가 달라서, 업무 오류로 바꾸는 일은 각 UseCase가 합니다.
 */
class TripNicknameDuplicatedException(
    cause: Throwable,
) : RuntimeException("같은 여행에 같은 여행방 닉네임이 이미 있습니다.", cause)
