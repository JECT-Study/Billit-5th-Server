package com.ject.module.member.implement

import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * 가입 때 닉네임을 비운 회원에게 줄 임의 닉네임을 만듭니다. 형용사 하나와 명사 하나를 공백 없이 붙입니다(예: `노래하는코끼리`).
 *
 * 두 단어 모두 5자 이하라 결과는 항상 닉네임 규칙(1~10자, 한글 완성형)을 지킵니다. 닉네임은 다른 회원과 겹쳐도 되므로 중복을 피하지 않습니다.
 */
@Component
class RandomNicknameGenerator(
    private val random: Random = Random.Default,
) {
    fun generate(): String = ADJECTIVES.random(random) + NOUNS.random(random)

    companion object {
        // shortcut: 단어 목록은 Docs 이슈 #5에서 정하기 전의 임시안, 확정되면 교체하거나 DB로 옮긴다.
        val ADJECTIVES = listOf("노래하는", "춤추는", "여행하는", "웃는", "용감한", "느긋한", "반짝이는", "졸린", "배고픈", "신나는")
        val NOUNS = listOf("코끼리", "고양이", "펭귄", "다람쥐", "여우", "판다", "거북이", "돌고래", "호랑이", "토끼")
    }
}
