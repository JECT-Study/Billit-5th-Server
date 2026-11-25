package com.ject.module.trip.implement

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class TripInviteCodeGeneratorTest :
    FunSpec({
        test("초대 코드는 영문 대문자와 숫자로 된 6자리다") {
            // given
            val generator = TripInviteCodeGenerator()

            // when
            val codes = List(1_000) { generator.generate() }

            // then
            codes.filterNot { it.matches(Regex("[A-Z0-9]{6}")) }.shouldBeEmpty()
            codes.flatMap { it.toList() }.toSet() shouldBe TripInviteCodeGenerator.CHARACTERS.toSet()
        }
    })
