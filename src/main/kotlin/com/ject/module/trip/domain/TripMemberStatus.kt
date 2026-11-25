package com.ject.module.trip.domain

/** 여행 참여자 상태입니다. 등록 인원에는 [MEMBER]와 [GUEST]만 셉니다. */
enum class TripMemberStatus {
    /** 참여 중인 로그인 참여자입니다. */
    MEMBER,

    /** 관리자가 이름으로 등록한 비로그인 참여자입니다. */
    GUEST,

    /** 여행을 나간 회원입니다. 지출과 정산에서 계속 참조합니다. */
    LEFT,

    /** 탈퇴한 회원입니다. 지출과 정산에서 계속 참조합니다. */
    WITHDRAWN,
    ;

    companion object {
        /** 등록 인원(registeredMemberCount)에 세는 상태입니다. */
        val REGISTERED: Set<TripMemberStatus> = setOf(MEMBER, GUEST)
    }
}
