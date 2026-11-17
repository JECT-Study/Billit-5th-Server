package com.ject.module.member.presentation.request

import com.ject.module.member.domain.Gender
import com.ject.module.member.domain.PainPoint
import com.ject.module.member.domain.PlatformType
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 가입 완료 요청입니다.
 *
 * 닉네임은 받지 않고 서버가 임의 닉네임을 부여합니다.
 * [platform]과 [appVersion]은 동의 이력에 남길 동의 기기와 앱 버전입니다. [survey]는 응답한 항목만 받습니다.
 * 만 18세 이상만 가입할 수 있어 [isAgeEligible]이 true여야 합니다.
 */
data class CompleteRegistrationRequest(
    @field:Valid
    val agreements: List<Agreement>,
    val platform: PlatformType,
    @field:Size(min = 1, max = APP_VERSION_MAX_LENGTH)
    @field:Pattern(regexp = NOT_BLANK_PATTERN)
    val appVersion: String,
    @field:NotNull
    @field:AssertTrue
    val isAgeEligible: Boolean?,
    @field:Valid
    val survey: Survey? = null,
) {
    data class Agreement(
        /** 가입 정보 조회에서 받은 약관 버전 식별자입니다. */
        @field:Pattern(regexp = TERMS_ID_PATTERN)
        val termsId: String,
        @field:NotNull
        val isAgreed: Boolean?,
    )

    data class Survey(
        val gender: Gender? = null,
        val isTripOrganizer: Boolean? = null,
        @field:Size(max = 2)
        val settlementPainPoints: List<PainPoint>? = null,
        /** [settlementPainPoints]에 `ETC`가 있을 때만 보낼 수 있습니다. */
        @field:Pattern(regexp = NOT_BLANK_PATTERN)
        @field:Size(max = OTHER_PAIN_POINT_MAX_LENGTH)
        val otherSettlementPainPoint: String? = null,
    )

    companion object {
        /** 약관 번호를 십진수 문자열로 받습니다. 약관 번호의 자릿수 안에서만 허용합니다. */
        private const val TERMS_ID_PATTERN = "[0-9]{1,18}"

        /** 공백이 아닌 문자를 하나 이상 포함합니다. 값이 없으면 검사하지 않습니다. */
        private const val NOT_BLANK_PATTERN = "(?s).*\\S.*"

        /** `member_consent.app_version`의 최대 길이입니다. */
        private const val APP_VERSION_MAX_LENGTH = 20

        /** `member_pain_point.other_reason_text`의 최대 길이입니다. */
        private const val OTHER_PAIN_POINT_MAX_LENGTH = 255
    }
}
