package com.ject.module.trip.implement

/**
 * 여행 초대 링크를 만듭니다.
 *
 * 주소는 `{기본 주소}/{초대 코드}`입니다. 예: `https://example.com/invitations/A7K2PQ`
 */
class TripInvitationUrlCreator(
    baseUrl: String,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    fun create(inviteCode: String): String = "$baseUrl/$inviteCode"
}
