package com.ject.module.member.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.member.domain.CompleteRegistrationCommand
import com.ject.module.member.domain.CompleteRegistrationCommand.PolicyAgreement
import com.ject.module.member.domain.Gender
import com.ject.module.member.domain.MemberErrorCode
import com.ject.module.member.domain.PainPoint
import com.ject.module.member.domain.PlatformType
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompleteRegistrationUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var completeRegistrationUseCase: CompleteRegistrationUseCase

    @Autowired
    @Qualifier("memberJwtDecoder")
    private lateinit var memberJwtDecoder: JwtDecoder

    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member_consent WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member_pain_point WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
            jdbc.update("DELETE FROM policy")
        }

        test("필수 약관에 동의하고 가입을 완료하면 회원을 가입 완료로 바꾸고 임의 닉네임, 설문, 동의 이력과 세션을 저장한다") {
            // given
            val memberId = createMember("PENDING")
            val termsId = createPolicy("TERMS", "v1", isRequired = true)
            val privacyId = createPolicy("PRIVACY", "v1", isRequired = true)
            val marketingId = createPolicy("MARKETING", "v1", isRequired = false)
            val command =
                CompleteRegistrationCommand(
                    agreements =
                        listOf(
                            PolicyAgreement(termsId, agreed = true),
                            PolicyAgreement(privacyId, agreed = true),
                            PolicyAgreement(marketingId, agreed = true),
                        ),
                    platform = PlatformType.IOS,
                    appVersion = "1.2.3",
                    gender = Gender.FEMALE,
                    isTreasurer = true,
                    painPoints = listOf(PainPoint.PAYMENT_HISTORY, PainPoint.ETC),
                    otherPainPointText = "환율 계산",
                )

            // when
            val tokens = completeRegistrationUseCase.complete(memberId, command)

            // then
            val member = findMember(memberId)
            val isUpdatedAtChanged =
                jdbc.queryForObject(
                    "SELECT updated_at > created_at FROM member WHERE id = ?",
                    Boolean::class.java,
                    memberId,
                )
            val consents =
                jdbc.queryForList(
                    "SELECT policy_id, channel::text AS channel, agreed, source::text AS source, platform::text AS platform, app_version " +
                        "FROM member_consent WHERE member_id = ?",
                    memberId,
                )
            val painPoints =
                jdbc.queryForList(
                    "SELECT pain_point::text AS pain_point, other_reason_text FROM member_pain_point WHERE member_id = ?",
                    memberId,
                )
            assertSoftly {
                member["status"] shouldBe "ACTIVE"
                (member["nickname"] as String) shouldMatch Regex("[가-힣]{1,10}")
                member["gender"] shouldBe "FEMALE"
                member["is_treasurer"] shouldBe true
                member["marketing_push_agreed_at"].shouldNotBeNull()
                member["ocr_agreed_at"].shouldBeNull()
                isUpdatedAtChanged shouldBe true
                consents.map {
                    listOf(
                        it["policy_id"],
                        it["channel"],
                        it["agreed"],
                        it["source"],
                        it["platform"],
                        it["app_version"],
                    )
                } shouldContainExactlyInAnyOrder
                    listOf(
                        listOf(termsId, null, true, "SIGNUP", "IOS", "1.2.3"),
                        listOf(privacyId, null, true, "SIGNUP", "IOS", "1.2.3"),
                        listOf(marketingId, "PUSH", true, "SIGNUP", "IOS", "1.2.3"),
                    )
                painPoints.map { it["pain_point"] to it["other_reason_text"] } shouldContainExactlyInAnyOrder
                    listOf("PAYMENT_HISTORY" to null, "ETC" to "환율 계산")
                countSessions(memberId) shouldBe 1
                memberJwtDecoder.decode(tokens.accessToken).subject shouldBe memberId.toString()
            }
        }

        test("선택 약관에 동의하지 않고 설문 없이 가입하면 거부 이력을 남기고 마케팅 동의 시각과 설문은 저장하지 않는다") {
            // given
            val memberId = createMember("PENDING")
            val termsId = createPolicy("TERMS", "v1", isRequired = true)
            val marketingId = createPolicy("MARKETING", "v1", isRequired = false)
            val command =
                command(agreements = listOf(PolicyAgreement(termsId, agreed = true), PolicyAgreement(marketingId, agreed = false)))

            // when
            completeRegistrationUseCase.complete(memberId, command)

            // then
            val member = findMember(memberId)
            val consents =
                jdbc.queryForList(
                    "SELECT policy_id, channel::text AS channel, agreed FROM member_consent WHERE member_id = ?",
                    memberId,
                )
            assertSoftly {
                member["status"] shouldBe "ACTIVE"
                member["marketing_push_agreed_at"].shouldBeNull()
                member["gender"].shouldBeNull()
                member["is_treasurer"].shouldBeNull()
                consents.map { listOf(it["policy_id"], it["channel"], it["agreed"]) } shouldContainExactlyInAnyOrder
                    listOf(listOf(termsId, null, true), listOf(marketingId, "PUSH", false))
                countRows("member_pain_point", memberId) shouldBe 0
            }
        }

        test("필수 약관에 동의하지 않으면 필수 약관 누락으로 거절하고 회원은 가입 중으로 남는다") {
            // given
            val memberId = createMember("PENDING")
            val termsId = createPolicy("TERMS", "v1", isRequired = true)
            val command = command(agreements = listOf(PolicyAgreement(termsId, agreed = false)))

            // when
            val exception = shouldThrow<BusinessException> { completeRegistrationUseCase.complete(memberId, command) }

            // then
            assertSoftly {
                exception.errorCode shouldBe MemberErrorCode.REQUIRED_AGREEMENT_MISSING
                findMember(memberId)["status"] shouldBe "PENDING"
                countRows("member_consent", memberId) shouldBe 0
                countSessions(memberId) shouldBe 0
            }
        }

        test("필수 약관을 요청에서 빼면 필수 약관 누락으로 거절한다") {
            // given
            val memberId = createMember("PENDING")
            createPolicy("TERMS", "v1", isRequired = true)
            val marketingId = createPolicy("MARKETING", "v1", isRequired = false)
            val command = command(agreements = listOf(PolicyAgreement(marketingId, agreed = true)))

            // when
            val exception = shouldThrow<BusinessException> { completeRegistrationUseCase.complete(memberId, command) }

            // then
            assertSoftly {
                exception.errorCode shouldBe MemberErrorCode.REQUIRED_AGREEMENT_MISSING
                findMember(memberId)["status"] shouldBe "PENDING"
            }
        }

        test("현재 버전이 아닌 약관에 동의하면 잘못된 요청으로 거절한다") {
            // given
            val memberId = createMember("PENDING")
            val oldTermsId = createPolicy("TERMS", "v1", isRequired = true, effectiveBefore = "2 days")
            createPolicy("TERMS", "v2", isRequired = true, effectiveBefore = "1 day")
            val command = command(agreements = listOf(PolicyAgreement(oldTermsId, agreed = true)))

            // when
            val exception = shouldThrow<BusinessException> { completeRegistrationUseCase.complete(memberId, command) }

            // then
            assertSoftly {
                exception.errorCode shouldBe CommonErrorCode.INVALID_REQUEST
                findMember(memberId)["status"] shouldBe "PENDING"
            }
        }

        test("같은 약관을 두 번 보내면 잘못된 요청으로 거절한다") {
            // given
            val memberId = createMember("PENDING")
            val termsId = createPolicy("TERMS", "v1", isRequired = true)
            val command = command(agreements = listOf(PolicyAgreement(termsId, agreed = true), PolicyAgreement(termsId, agreed = true)))

            // when
            val exception = shouldThrow<BusinessException> { completeRegistrationUseCase.complete(memberId, command) }

            // then
            exception.errorCode shouldBe CommonErrorCode.INVALID_REQUEST
        }

        test("기타를 선택하지 않고 기타 입력 내용을 보내면 잘못된 요청으로 거절한다") {
            // given
            val memberId = createMember("PENDING")
            val command = command(painPoints = listOf(PainPoint.SHARE_CALCULATION), otherPainPointText = "환율 계산")

            // when
            val exception = shouldThrow<BusinessException> { completeRegistrationUseCase.complete(memberId, command) }

            // then
            assertSoftly {
                exception.errorCode shouldBe CommonErrorCode.INVALID_REQUEST
                findMember(memberId)["status"] shouldBe "PENDING"
            }
        }

        test("임의 닉네임이 다른 회원의 닉네임과 같아도 가입을 완료한다") {
            // given
            val memberId = createMember("PENDING")
            val termsId = createPolicy("TERMS", "v1", isRequired = true)
            completeRegistrationUseCase.complete(memberId, command(listOf(PolicyAgreement(termsId, agreed = true))))
            val nickname = findMember(memberId)["nickname"] as String

            // when
            val otherMemberId = createMember("ACTIVE", nickname)

            // then
            findMember(otherMemberId)["nickname"] shouldBe nickname
        }

        test("회원을 가입 완료로 바꾼 뒤 설문 저장이 실패하면 회원 변경과 동의 이력까지 롤백하고 세션을 남기지 않는다") {
            // given
            val memberId = createMember("PENDING")
            val termsId = createPolicy("TERMS", "v1", isRequired = true)
            jdbc.update(
                "INSERT INTO member_pain_point (member_id, pain_point, created_at, updated_at) VALUES (?, 'ETC', now(), now())",
                memberId,
            )
            val command = command(agreements = listOf(PolicyAgreement(termsId, agreed = true)), painPoints = listOf(PainPoint.ETC))

            // when
            val exception = shouldThrow<DataIntegrityViolationException> { completeRegistrationUseCase.complete(memberId, command) }

            // then
            val member = findMember(memberId)
            assertSoftly {
                exception.shouldNotBeNull()
                member["status"] shouldBe "PENDING"
                member["nickname"].shouldBeNull()
                countRows("member_consent", memberId) shouldBe 0
                countSessions(memberId) shouldBe 0
            }
        }

        test("이미 가입을 마친 회원이 다시 요청하면 저장된 가입 내용을 바꾸지 않고 새 세션의 토큰을 발급한다") {
            // given
            val memberId = createMember("PENDING")
            val termsId = createPolicy("TERMS", "v1", isRequired = true)
            val first = completeRegistrationUseCase.complete(memberId, command(listOf(PolicyAgreement(termsId, agreed = true))))
            val nickname = findMember(memberId)["nickname"]
            val retry = command(agreements = listOf(PolicyAgreement(termsId, agreed = true)))

            // when
            val second = completeRegistrationUseCase.complete(memberId, retry)

            // then
            assertSoftly {
                second.refreshToken shouldNotBe first.refreshToken
                memberJwtDecoder.decode(second.accessToken).subject shouldBe memberId.toString()
                findMember(memberId)["nickname"] shouldBe nickname
                countRows("member_consent", memberId) shouldBe 1
                countSessions(memberId) shouldBe 2
            }
        }

        test("같은 회원의 가입 완료 요청이 동시에 오면 가입 내용은 한 번만 저장하고 두 요청 모두 토큰을 발급한다") {
            // given
            val memberId = createMember("PENDING")
            val termsId = createPolicy("TERMS", "v1", isRequired = true)
            val command =
                command(agreements = listOf(PolicyAgreement(termsId, agreed = true)), painPoints = listOf(PainPoint.COMPLEX_RELATION))
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)

            // when
            val results =
                try {
                    (1..2)
                        .map {
                            executor.submit<Result<String>> {
                                barrier.await(5, TimeUnit.SECONDS)
                                runCatching { completeRegistrationUseCase.complete(memberId, command).accessToken }
                            }
                        }.map { it.get(10, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }

            // then
            assertSoftly {
                results.map { it.isSuccess } shouldBe listOf(true, true)
                findMember(memberId)["status"] shouldBe "ACTIVE"
                countRows("member_consent", memberId) shouldBe 1
                countRows("member_pain_point", memberId) shouldBe 1
                countSessions(memberId) shouldBe 2
            }
        }

        test("탈퇴한 회원의 가입 완료 요청은 인증 실패로 거절한다") {
            // given
            val memberId = createMember("WITHDRAWN")

            // when
            val exception = shouldThrow<BusinessException> { completeRegistrationUseCase.complete(memberId, command()) }

            // then
            assertSoftly {
                exception.errorCode shouldBe CommonErrorCode.UNAUTHORIZED
                countSessions(memberId) shouldBe 0
            }
        }
    }

    private fun command(
        agreements: List<PolicyAgreement> = emptyList(),
        painPoints: List<PainPoint> = emptyList(),
        otherPainPointText: String? = null,
    ): CompleteRegistrationCommand =
        CompleteRegistrationCommand(
            agreements = agreements,
            platform = PlatformType.ANDROID,
            appVersion = "1.0.0",
            gender = null,
            isTreasurer = null,
            painPoints = painPoints,
            otherPainPointText = otherPainPointText,
        )

    private fun createMember(
        status: String,
        nickname: String? = null,
    ): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, nickname, status, created_at, updated_at) " +
                        "VALUES ('GOOGLE', ?, ?::member_status, now() - interval '1 hour', now() - interval '1 hour') RETURNING id",
                    Long::class.java,
                    nickname,
                    status,
                ),
            )
        memberIds.add(id)
        return id
    }

    private fun createPolicy(
        type: String,
        version: String,
        isRequired: Boolean,
        effectiveBefore: String = "1 hour",
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO policy (type, version, title, content, is_required, effective_at, created_at) " +
                    "VALUES (?::policy_type, ?, ?, '약관 전문', ?, now() - ?::interval, now()) RETURNING id",
                Long::class.java,
                type,
                version,
                "$type $version",
                isRequired,
                effectiveBefore,
            ),
        )

    private fun findMember(memberId: Long): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT status::text AS status, nickname, gender::text AS gender, is_treasurer, marketing_push_agreed_at, ocr_agreed_at " +
                "FROM member WHERE id = ?",
            memberId,
        )

    private fun countSessions(memberId: Long): Int = countRows("auth_session", memberId)

    private fun countRows(
        table: String,
        memberId: Long,
    ): Int = checkNotNull(jdbc.queryForObject("SELECT count(*) FROM $table WHERE member_id = ?", Int::class.java, memberId))
}
