package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.trip.domain.TripDetailResult
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID

class GetTripDetailUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var getTripDetailUseCase: GetTripDetailUseCase

    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("참여자는 관리자, 로그인 참여자, 비로그인 참여자, 나간 회원, 탈퇴 회원 순이고 탈퇴 회원은 먼저 탈퇴한 순서로 '탈퇴한 사용자 N'이 된다") {
            // given
            val adminId = createMember()
            val laterJoinedId = createMember()
            val earlierJoinedId = createMember()
            val laterLeftId = createMember()
            val earlierLeftId = createMember()
            val laterWithdrawnId = createMember(status = "WITHDRAWN", withdrawnAt = "2026-11-12T00:00:00Z")
            val earlierWithdrawnId = createMember(status = "WITHDRAWN", withdrawnAt = "2026-11-11T00:00:00Z")
            val tripId = createTrip()
            // 행 ID 순서와 기대 순서가 다르도록 뒤 순서의 참여자를 먼저 저장합니다.
            val laterWithdrawn = addTripMember(tripId, laterWithdrawnId, "도윤", role = "MEMBER", status = "WITHDRAWN")
            val earlierWithdrawn = addTripMember(tripId, earlierWithdrawnId, "시우", role = "MEMBER", status = "WITHDRAWN")
            val laterLeft = addTripMember(tripId, laterLeftId, "하준", role = "MEMBER", status = "LEFT", leftAt = "2026-11-10T00:00:00Z")
            val earlierLeft = addTripMember(tripId, earlierLeftId, "예준", role = "MEMBER", status = "LEFT", leftAt = "2026-11-09T00:00:00Z")
            val laterGuest = addTripMember(tripId, null, "지아", role = "MEMBER", status = "GUEST", createdAt = "2026-11-08T00:00:00Z")
            val earlierGuest = addTripMember(tripId, null, "서연", role = "MEMBER", status = "GUEST", createdAt = "2026-11-07T00:00:00Z")
            val laterJoined =
                addTripMember(tripId, laterJoinedId, "지훈", role = "MEMBER", status = "MEMBER", joinedAt = "2026-11-06T00:00:00Z")
            val earlierJoined =
                addTripMember(tripId, earlierJoinedId, "수아", role = "MEMBER", status = "MEMBER", joinedAt = "2026-11-05T00:00:00Z")
            val admin = addTripMember(tripId, adminId, "민지", role = "ADMIN", status = "MEMBER")

            // when
            val result = getTripDetailUseCase.get(laterJoinedId, tripId)

            // then
            assertSoftly {
                result.participants shouldBe
                    listOf(
                        participant(admin, adminId, "민지", TripMemberRole.ADMIN, TripMemberStatus.MEMBER),
                        participant(earlierJoined, earlierJoinedId, "수아", TripMemberRole.MEMBER, TripMemberStatus.MEMBER),
                        participant(laterJoined, laterJoinedId, "지훈", TripMemberRole.MEMBER, TripMemberStatus.MEMBER),
                        participant(earlierGuest, null, "서연", TripMemberRole.MEMBER, TripMemberStatus.GUEST),
                        participant(laterGuest, null, "지아", TripMemberRole.MEMBER, TripMemberStatus.GUEST),
                        participant(earlierLeft, earlierLeftId, "예준", TripMemberRole.MEMBER, TripMemberStatus.LEFT),
                        participant(laterLeft, laterLeftId, "하준", TripMemberRole.MEMBER, TripMemberStatus.LEFT),
                        participant(earlierWithdrawn, null, "탈퇴한 사용자 1", TripMemberRole.MEMBER, TripMemberStatus.WITHDRAWN),
                        participant(laterWithdrawn, null, "탈퇴한 사용자 2", TripMemberRole.MEMBER, TripMemberStatus.WITHDRAWN),
                    )
                result.registeredMemberCount shouldBe 5
                result.myRole shouldBe TripMemberRole.MEMBER
            }
        }

        test("여행이 없거나 삭제됐으면 404, 참여하지 않았거나 나간 회원이면 403으로 거절한다") {
            // given
            val adminId = createMember()
            val leftMemberId = createMember()
            val outsiderId = createMember()
            val tripId = createTrip()
            addTripMember(tripId, adminId, "민지", role = "ADMIN", status = "MEMBER")
            addTripMember(tripId, leftMemberId, "하준", role = "MEMBER", status = "LEFT", leftAt = "2026-11-10T00:00:00Z")
            val deletedTripId = createTrip(deletedAt = "2026-11-10T00:00:00Z")
            addTripMember(deletedTripId, adminId, "민지", role = "ADMIN", status = "MEMBER")
            val missingTripId = tripId + 1_000_000

            // when
            val failures =
                mapOf(
                    "없는 여행" to shouldThrow<BusinessException> { getTripDetailUseCase.get(adminId, missingTripId) },
                    "삭제된 여행" to shouldThrow<BusinessException> { getTripDetailUseCase.get(adminId, deletedTripId) },
                    "참여하지 않은 회원" to shouldThrow<BusinessException> { getTripDetailUseCase.get(outsiderId, tripId) },
                    "나간 회원" to shouldThrow<BusinessException> { getTripDetailUseCase.get(leftMemberId, tripId) },
                )

            // then
            failures.mapValues { it.value.errorCode } shouldBe
                mapOf(
                    "없는 여행" to CommonErrorCode.NOT_FOUND,
                    "삭제된 여행" to CommonErrorCode.NOT_FOUND,
                    "참여하지 않은 회원" to CommonErrorCode.FORBIDDEN,
                    "나간 회원" to CommonErrorCode.FORBIDDEN,
                )
        }
    }

    private fun participant(
        tripMemberId: Long,
        memberId: Long?,
        displayName: String,
        role: TripMemberRole,
        status: TripMemberStatus,
    ) = TripDetailResult.Participant(
        tripMemberId = tripMemberId,
        memberId = memberId,
        displayName = displayName,
        role = role,
        participantStatus = status,
        shareAmountKrw = BigDecimal("0.00"),
    )

    private fun createMember(
        status: String = "ACTIVE",
        withdrawnAt: String? = null,
    ): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, withdrawn_at, created_at, updated_at) " +
                        "VALUES ('GOOGLE', ?::member_status, ?::timestamptz, now(), now()) RETURNING id",
                    Long::class.java,
                    status,
                    withdrawnAt,
                ),
            )
        memberIds.add(id)
        return id
    }

    private fun createTrip(deletedAt: String? = null): Long {
        val key = UUID.randomUUID()
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, " +
                        "invite_code, idempotency_key, deleted_at, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', '2026-11-05', '2026-11-07', 'JP', 'JPY', 10, 'CONFIRMED', ?, ?, ?::timestamptz, now(), now()) " +
                        "RETURNING id",
                    Long::class.java,
                    key.toString().replace("-", "").take(20),
                    key,
                    deletedAt,
                ),
            )
        tripIds.add(id)
        return id
    }

    private fun addTripMember(
        tripId: Long,
        memberId: Long?,
        tripNickname: String,
        role: String,
        status: String,
        joinedAt: String? = null,
        leftAt: String? = null,
        createdAt: String = "2026-11-01T00:00:00Z",
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, joined_at, left_at, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?::trip_member_role, ?::trip_member_status, ?::timestamptz, ?::timestamptz, ?::timestamptz, now()) " +
                    "RETURNING id",
                Long::class.java,
                tripId,
                memberId,
                tripNickname,
                role,
                status,
                joinedAt,
                leftAt,
                createdAt,
            ),
        )
}
