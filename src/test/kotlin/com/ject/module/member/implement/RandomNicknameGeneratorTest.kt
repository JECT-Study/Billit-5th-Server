package com.ject.module.member.implement

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class RandomNicknameGeneratorTest :
    FunSpec({
        test("모든 형용사와 명사 조합이 닉네임 규칙(1~10자, 허용 문자)을 지킨다") {
            // given
            val rule = Regex("[가-힣A-Za-z0-9]{1,10}")

            // when
            val combinations =
                RandomNicknameGenerator.ADJECTIVES.flatMap { adjective -> RandomNicknameGenerator.NOUNS.map { adjective + it } }

            // then
            combinations.forEach { rule.matches(it) shouldBe true }
        }

        test("형용사 하나와 명사 하나를 공백 없이 붙인다") {
            // given
            val generator = RandomNicknameGenerator(Random(42))

            // when
            val nickname = generator.generate()

            // then
            val adjective =
                RandomNicknameGenerator.ADJECTIVES.single {
                    nickname.startsWith(it) &&
                        nickname.removePrefix(it) in RandomNicknameGenerator.NOUNS
                }
            nickname.removePrefix(adjective) shouldBeIn RandomNicknameGenerator.NOUNS
        }
    })
