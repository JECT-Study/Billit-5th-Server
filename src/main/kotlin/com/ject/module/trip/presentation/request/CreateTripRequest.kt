package com.ject.module.trip.presentation.request

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.ject.module.trip.domain.TripCountryCode
import com.ject.module.trip.domain.TripCurrency
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.LocalDate

/**
 * 여행 생성 요청입니다. 명세의 `ListedCountryTripCreate`(대표 국가 선택)와 `CustomCountryTripCreate`(기타 국가 입력)를 함께 받습니다.
 *
 * [name]과 [customCountryName]은 앞뒤 공백을 지운 값으로 검사하므로 형식 검사가 앞뒤 공백을 허용합니다. 저장할 값은 Controller가 공백을 지워 넘깁니다.
 * [customCountryName]은 [countryCode]가 `ETC`일 때만 보낼 수 있고 `null`은 받지 않습니다. 대표 국가와의 조합과 여행 기간의 순서는 UseCase가 확인합니다.
 * [tripNickname]은 생성자의 여행방 닉네임이며 공백을 지우지 않고 검사합니다.
 */
data class CreateTripRequest(
    @field:Pattern(regexp = NAME_PATTERN)
    val name: String,
    val countryCode: TripCountryCode,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:Pattern(regexp = CUSTOM_COUNTRY_NAME_PATTERN)
    val customCountryName: String? = null,
    val baseCurrency: TripCurrency,
    val startDate: LocalDate,
    val endDate: LocalDate,
    @field:NotNull
    @field:Min(MIN_MEMBER_CAPACITY)
    @field:Max(MAX_MEMBER_CAPACITY)
    val memberCapacity: Int?,
    @field:Pattern(regexp = TRIP_NICKNAME_PATTERN)
    val tripNickname: String,
) {
    companion object {
        /** 앞뒤 공백을 뺀 값이 한글 완성형, 영문, 숫자와 가운데 공백으로 된 1~15자입니다. */
        private const val NAME_PATTERN =
            "[\\p{javaWhitespace}\\p{gc=Zs}]*[가-힣A-Za-z0-9](?:[가-힣A-Za-z0-9 ]{0,13}[가-힣A-Za-z0-9])?[\\p{javaWhitespace}\\p{gc=Zs}]*"

        /** 앞뒤 공백을 뺀 값이 한글 완성형과 영문으로 된 1~10자입니다. 가운데 공백은 받지 않습니다. */
        private const val CUSTOM_COUNTRY_NAME_PATTERN = "[\\p{javaWhitespace}\\p{gc=Zs}]*[가-힣A-Za-z]{1,10}[\\p{javaWhitespace}\\p{gc=Zs}]*"

        /** 한글 완성형, 영문과 숫자로 된 1~10자입니다. 계정 닉네임과 같은 규칙입니다. */
        private const val TRIP_NICKNAME_PATTERN = "[가-힣A-Za-z0-9]{1,10}"

        /** 생성자를 포함한 총인원의 범위입니다. */
        private const val MIN_MEMBER_CAPACITY = 1L
        private const val MAX_MEMBER_CAPACITY = 10L
    }
}
