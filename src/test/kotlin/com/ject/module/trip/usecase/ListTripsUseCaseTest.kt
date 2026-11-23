package com.ject.module.trip.usecase

import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripScheduleStatus
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class ListTripsUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var listTripsUseCase: ListTripsUseCase

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

        test("확정 후 여행은 오늘이 시작일 전이면 UPCOMING, 시작일부터 종료일까지 ONGOING, 종료일 뒤면 ENDED이고 필터도 같은 기준으로 나눈다") {
            // given
            val memberId = createMember()
            val tripId = createTrip(status = "CONFIRMED", startDate = "2026-11-10", endDate = "2026-11-12")
            addTripMember(tripId, memberId, "민지", role = "ADMIN", status = "MEMBER")
            val expected =
                mapOf(
                    "2026-11-09" to TripScheduleStatus.UPCOMING,
                    "2026-11-10" to TripScheduleStatus.ONGOING,
                    "2026-11-12" to TripScheduleStatus.ONGOING,
                    "2026-11-13" to TripScheduleStatus.ENDED,
                )

            // when
            val scheduleStatuses = expected.mapValues { list(memberId, LocalDate.parse(it.key)).items.single().scheduleStatus }
            val matchedFilters =
                expected.mapValues { (today, _) ->
                    TripScheduleStatus.entries.filter { list(memberId, LocalDate.parse(today), it).items.isNotEmpty() }
                }

            // then
            assertSoftly {
                scheduleStatuses shouldBe expected
                matchedFilters shouldBe expected.mapValues { listOf(it.value) }
            }
        }

        test("확정 전 여행은 시작일과 종료일이 지났어도 INVITING이고 INVITING 필터에만 들어간다") {
            // given
            val memberId = createMember()
            val tripId = createTrip(status = "UNCONFIRMED", startDate = "2026-11-01", endDate = "2026-11-02")
            addTripMember(tripId, memberId, "민지", role = "ADMIN", status = "MEMBER")
            val today = LocalDate.parse("2026-11-20")

            // when
            val all = list(memberId, today)
            val matchedFilters = TripScheduleStatus.entries.filter { list(memberId, today, it).items.isNotEmpty() }

            // then
            assertSoftly {
                all.items.single().scheduleStatus shouldBe TripScheduleStatus.INVITING
                matchedFilters shouldBe listOf(TripScheduleStatus.INVITING)
            }
        }

        test("나간 여행, 삭제된 여행과 참여하지 않은 여행은 빼고, 등록 인원에는 나간 회원과 탈퇴 회원을 세지 않는다") {
            // given
            val memberId = createMember()
            val otherMemberId = createMember()
            val leftMemberId = createMember()
            val withdrawnMemberId = createMember(status = "WITHDRAWN", withdrawnAt = "2026-11-03T00:00:00Z")
            val joinedTripId = createTrip()
            addTripMember(joinedTripId, otherMemberId, "민지", role = "ADMIN", status = "MEMBER")
            addTripMember(joinedTripId, memberId, "지훈", role = "MEMBER", status = "MEMBER")
            addTripMember(joinedTripId, null, "서연", role = "MEMBER", status = "GUEST")
            addTripMember(joinedTripId, leftMemberId, "하준", role = "MEMBER", status = "LEFT")
            addTripMember(joinedTripId, withdrawnMemberId, "도윤", role = "MEMBER", status = "WITHDRAWN")
            val leftTripId = createTrip()
            addTripMember(leftTripId, otherMemberId, "민지", role = "ADMIN", status = "MEMBER")
            addTripMember(leftTripId, memberId, "지훈", role = "MEMBER", status = "LEFT")
            val deletedTripId = createTrip(deletedAt = "2026-11-05T00:00:00Z")
            addTripMember(deletedTripId, memberId, "지훈", role = "ADMIN", status = "MEMBER")
            val otherTripId = createTrip()
            addTripMember(otherTripId, otherMemberId, "민지", role = "ADMIN", status = "MEMBER")

            // when
            val result = list(memberId, LocalDate.parse("2026-11-20"))

            // then
            val trip = result.items.single()
            assertSoftly {
                result.totalElements shouldBe 1
                trip.tripId shouldBe joinedTripId
                trip.registeredMemberCount shouldBe 3
                trip.myRole shouldBe TripMemberRole.MEMBER
                trip.myShareAmountKrw shouldBe BigDecimal("0.00")
            }
        }

        test("만든 시각의 역순, 같으면 여행 ID의 역순으로 정렬하고 필터를 적용한 결과에 페이지를 나눈다") {
            // given
            val memberId = createMember()
            val olderTripId = createTrip(createdAt = "2026-11-01T00:00:00Z")
            val sameTimeTripIds = List(3) { createTrip(createdAt = "2026-11-02T00:00:00Z") }
            val filteredOutTripId = createTrip(status = "CONFIRMED", createdAt = "2026-11-03T00:00:00Z")
            (listOf(olderTripId, filteredOutTripId) + sameTimeTripIds).forEach {
                addTripMember(it, memberId, "민지", role = "ADMIN", status = "MEMBER")
            }
            val today = LocalDate.parse("2026-11-20")

            // when
            val firstPage = list(memberId, today, TripScheduleStatus.INVITING, page = 0, size = 3)
            val secondPage = list(memberId, today, TripScheduleStatus.INVITING, page = 1, size = 3)
            val beyondLastPage = list(memberId, today, TripScheduleStatus.INVITING, page = 2, size = 3)
            val unfiltered = list(memberId, today)

            // then
            assertSoftly {
                firstPage.items.map { it.tripId } shouldBe sameTimeTripIds.reversed()
                firstPage.totalElements shouldBe 4
                firstPage.hasNext shouldBe true
                secondPage.items.map { it.tripId } shouldBe listOf(olderTripId)
                secondPage.hasNext shouldBe false
                beyondLastPage.items shouldBe emptyList()
                beyondLastPage.totalElements shouldBe 4
                beyondLastPage.hasNext shouldBe false
                unfiltered.items.map { it.tripId } shouldBe listOf(filteredOutTripId) + sameTimeTripIds.reversed() + olderTripId
            }
        }
    }

    private fun list(
        memberId: Long,
        today: LocalDate,
        filter: TripScheduleStatus? = null,
        page: Int = 0,
        size: Int = 20,
    ) = listTripsUseCase.list(memberId, filter, today, page, size)

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

    private fun createTrip(
        status: String = "UNCONFIRMED",
        startDate: String = "2026-11-05",
        endDate: String = "2026-11-07",
        createdAt: String = "2026-11-01T00:00:00Z",
        deletedAt: String? = null,
    ): Long {
        val key = UUID.randomUUID()
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, " +
                        "invite_code, idempotency_key, deleted_at, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', ?::date, ?::date, 'JP', 'JPY', 10, ?::trip_status, ?, ?, " +
                        "?::timestamptz, ?::timestamptz, now()) RETURNING id",
                    Long::class.java,
                    startDate,
                    endDate,
                    status,
                    key.toString().replace("-", "").take(20),
                    key,
                    deletedAt,
                    createdAt,
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
    ) {
        jdbc.update(
            "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?::trip_member_role, ?::trip_member_status, now(), now())",
            tripId,
            memberId,
            tripNickname,
            role,
            status,
        )
    }
}
