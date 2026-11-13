package com.ject.module.member.presentation.response

data class RegistrationInfoResponse(
    val status: RegistrationStatus,
    val socialDisplayName: String?,
    val terms: List<Terms>,
    val name: String?,
    val age: Int?,
) {
    data class Terms(
        val termsId: String,
        val title: String,
        val isRequired: Boolean,
        val contentUrl: String,
    )
}
