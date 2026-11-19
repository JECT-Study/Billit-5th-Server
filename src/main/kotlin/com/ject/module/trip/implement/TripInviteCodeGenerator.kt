package com.ject.module.trip.implement

import org.springframework.stereotype.Component
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * 여행 초대 코드를 만듭니다. 영문 대문자와 숫자를 조합한 6자리입니다.
 *
 * 코드를 추측하기 어렵도록 [SecureRandom]으로 만듭니다. 다른 여행의 코드와 겹치는지는 확인하지 않으며, 중복은 저장할 때 유니크 제약으로 막습니다.
 */
@Component
class TripInviteCodeGenerator(
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    fun generate(): String = String(CharArray(LENGTH) { CHARACTERS.random(random) })

    companion object {
        const val LENGTH = 6
        const val CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
