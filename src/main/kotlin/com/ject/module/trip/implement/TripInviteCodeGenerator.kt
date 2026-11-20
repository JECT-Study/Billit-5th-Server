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

        /** [code]가 이 생성기가 만들 수 있는 형식인지 확인합니다. 형식이 다른 코드는 저장된 여행이 없으므로 조회 전에 걸러 냅니다. */
        fun isWellFormed(code: String): Boolean = code.length == LENGTH && code.all { it in CHARACTERS }
    }
}
