package com.ject.module.trip.presentation.request

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.ject.module.trip.domain.TripCountryCode
import com.ject.module.trip.domain.TripCurrency
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.time.LocalDate

/**
 * 여행 정보 수정 요청입니다. 명세의 `TripUpdate`입니다.
 *
 * 모든 필드는 선택이고 `null`을 보내면 거절합니다. 생략한 필드만 `null`로 남습니다. 각 필드의 형식은 [CreateTripRequest]와 같습니다.
 * 대표 국가와 국가명의 조합, 여행 기간의 순서와 총인원 변경 가능 여부는 저장된 값과 합쳐 UseCase가 확인합니다.
 */
data class UpdateTripRequest(
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:Pattern(regexp = NAME_PATTERN)
    val name: String? = null,
    @param:JsonSetter(nulls = Nulls.FAIL)
    val countryCode: TripCountryCode? = null,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:Pattern(regexp = CUSTOM_COUNTRY_NAME_PATTERN)
    val customCountryName: String? = null,
    @param:JsonSetter(nulls = Nulls.FAIL)
    val baseCurrency: TripCurrency? = null,
    @param:JsonSetter(nulls = Nulls.FAIL)
    val startDate: LocalDate? = null,
    @param:JsonSetter(nulls = Nulls.FAIL)
    val endDate: LocalDate? = null,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:Min(MIN_MEMBER_CAPACITY)
    @field:Max(MAX_MEMBER_CAPACITY)
    val memberCapacity: Int? = null,
) {
    /** 필드를 하나도 보내지 않은 요청입니다. */
    fun isEmpty(): Boolean =
        name == null && countryCode == null && customCountryName == null && baseCurrency == null &&
            startDate == null && endDate == null && memberCapacity == null

    companion object {
        /** 앞뒤 공백을 뺀 값이 한글 완성형, 영문, 숫자와 가운데 공백으로 된 1~15자입니다. */
        private const val NAME_PATTERN =
            "[\\p{javaWhitespace}\\p{gc=Zs}]*[가-힣A-Za-z0-9](?:[가-힣A-Za-z0-9 ]{0,13}[가-힣A-Za-z0-9])?[\\p{javaWhitespace}\\p{gc=Zs}]*"

        /** 앞뒤 공백을 뺀 값이 한글 완성형과 영문으로 된 1~10자입니다. 가운데 공백은 받지 않습니다. */
        private const val CUSTOM_COUNTRY_NAME_PATTERN = "[\\p{javaWhitespace}\\p{gc=Zs}]*[가-힣A-Za-z]{1,10}[\\p{javaWhitespace}\\p{gc=Zs}]*"

        /** 생성자를 포함한 총인원의 범위입니다. */
        private const val MIN_MEMBER_CAPACITY = 1L
        private const val MAX_MEMBER_CAPACITY = 10L
    }
}
