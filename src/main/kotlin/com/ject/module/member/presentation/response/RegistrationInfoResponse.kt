package com.ject.module.member.presentation.response

data class RegistrationInfoResponse(
    val status: RegistrationStatus,
    val socialDisplayName: String?,
    val terms: List<Terms>,
) {
    data class Terms(
        val termsId: String,
        val title: String,
        val isRequired: Boolean,
        val contentUrl: String,
    )
}
