package com.ject.module.trip.infra.persistence.jdsl

import com.ject.module.trip.domain.Trip
import com.ject.module.trip.domain.TripListItemResult
import com.ject.module.trip.domain.TripMember
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripScheduleStatus
import com.ject.module.trip.domain.TripStatus
import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.entity.Entity
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.hibernate.extension.createQuery
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class TripJdslRepository(
    private val entityManager: EntityManager,
    private val jpqlRenderContext: JpqlRenderContext,
) {
    fun findMyTrips(
        memberId: Long,
        filter: TripScheduleStatus?,
        today: LocalDate,
        offset: Int,
        limit: Int,
    ): List<TripListItemResult> {
        val query =
            jpql {
                val me = entity(TripMember::class, "me")
                val registered = entity(TripMember::class, "registered")
                selectNew<TripListItemResult>(
                    path(Trip::id),
                    path(Trip::name),
                    path(Trip::countryCode),
                    path(Trip::customCountryName),
                    path(Trip::startDate),
                    path(Trip::endDate),
                    path(Trip::memberCapacity),
                    select(count(registered(TripMember::id)))
                        .from(registered)
                        .where(
                            and(
                                registered(TripMember::tripId).equal(path(Trip::id)),
                                registered(TripMember::status).`in`(TripMemberStatus.REGISTERED),
                            ),
                        ).asSubquery(),
                    path(Trip::status),
                    me(TripMember::role),
                ).from(entity(Trip::class), join(me).on(me(TripMember::tripId).equal(path(Trip::id))))
                    .where(myTripCondition(me, memberId, filter, today))
                    .orderBy(path(Trip::createdAt).desc(), path(Trip::id).desc())
            }

        return entityManager
            .createQuery(query, jpqlRenderContext)
            .apply {
                firstResult = offset
                maxResults = limit
            }.resultList
    }

    fun countMyTrips(
        memberId: Long,
        filter: TripScheduleStatus?,
        today: LocalDate,
    ): Long {
        val query =
            jpql {
                val me = entity(TripMember::class, "me")
                select(count(path(Trip::id)))
                    .from(entity(Trip::class), join(me).on(me(TripMember::tripId).equal(path(Trip::id))))
                    .where(myTripCondition(me, memberId, filter, today))
            }

        return entityManager.createQuery(query, jpqlRenderContext).singleResult
    }

    /** 회원이 참여 중인 삭제되지 않은 여행 중 [filter]의 일정 상태에 맞는 여행입니다. 확정 전 여행은 날짜와 관계없이 초대중으로 봅니다. */
    private fun Jpql.myTripCondition(
        me: Entity<TripMember>,
        memberId: Long,
        filter: TripScheduleStatus?,
        today: LocalDate,
    ): Predicate {
        val scheduleCondition =
            when (filter) {
                null -> {
                    null
                }

                TripScheduleStatus.INVITING -> {
                    path(Trip::status).equal(TripStatus.UNCONFIRMED)
                }

                TripScheduleStatus.UPCOMING -> {
                    and(path(Trip::status).equal(TripStatus.CONFIRMED), path(Trip::startDate).greaterThan(today))
                }

                TripScheduleStatus.ONGOING -> {
                    and(
                        path(Trip::status).equal(TripStatus.CONFIRMED),
                        path(Trip::startDate).lessThanOrEqualTo(today),
                        path(Trip::endDate).greaterThanOrEqualTo(today),
                    )
                }

                TripScheduleStatus.ENDED -> {
                    and(path(Trip::status).equal(TripStatus.CONFIRMED), path(Trip::endDate).lessThan(today))
                }
            }
        return and(
            me(TripMember::memberId).equal(memberId),
            me(TripMember::status).equal(TripMemberStatus.MEMBER),
            path(Trip::deletedAt).isNull(),
            scheduleCondition,
        )
    }
}
