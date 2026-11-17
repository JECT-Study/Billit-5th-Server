package com.ject.module.member.presentation

import com.ject.global.response.ApiResponse
import com.ject.module.member.domain.CompleteRegistrationCommand
import com.ject.module.member.presentation.request.CompleteRegistrationRequest
import com.ject.module.member.presentation.response.RegistrationCompletedResponse
import com.ject.module.member.presentation.response.RegistrationInfoResponse
import com.ject.module.member.presentation.response.RegistrationStatus
import com.ject.module.member.usecase.CompleteRegistrationUseCase
import com.ject.module.member.usecase.GetRegistrationUseCase
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 가입 API입니다. 두 API 모두 가입 전용 토큰으로 호출합니다. */
@RestController
@RequestMapping("/api/v1")
class MemberController(
    private val getRegistrationUseCase: GetRegistrationUseCase,
    private val completeRegistrationUseCase: CompleteRegistrationUseCase,
) {
    @GetMapping("/me/registration")
    fun getRegistration(
        @AuthenticationPrincipal registrationToken: Jwt,
    ): ApiResponse<RegistrationInfoResponse> {
        val registration = getRegistrationUseCase.get(memberId(registrationToken))

        return ApiResponse(
            RegistrationInfoResponse(
                status = RegistrationStatus.SIGNING_UP,
                socialDisplayName = registration.socialDisplayName,
                terms =
                    registration.terms.map {
                        RegistrationInfoResponse.Terms(
                            termsId = it.policyId.toString(),
                            title = it.title,
                            isRequired = it.isRequired,
                            contentUrl = it.contentUrl,
                        )
                    },
            ),
        )
    }

    @PostMapping("/me/registration")
    fun completeRegistration(
        @AuthenticationPrincipal registrationToken: Jwt,
        @Valid @RequestBody request: CompleteRegistrationRequest,
    ): ApiResponse<RegistrationCompletedResponse> {
        val command =
            CompleteRegistrationCommand(
                agreements =
                    request.agreements.map {
                        CompleteRegistrationCommand.PolicyAgreement(policyId = it.termsId.toLong(), agreed = checkNotNull(it.isAgreed))
                    },
                platform = request.platform,
                appVersion = request.appVersion,
                gender = request.survey?.gender,
                isTreasurer = request.survey?.isTripOrganizer,
                painPoints = request.survey?.settlementPainPoints.orEmpty(),
                otherPainPointText = request.survey?.otherSettlementPainPoint,
            )

        val tokens = completeRegistrationUseCase.complete(memberId(registrationToken), command)

        return ApiResponse(RegistrationCompletedResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken))
    }

    private fun memberId(registrationToken: Jwt): Long = checkNotNull(registrationToken.subject).toLong()
}
