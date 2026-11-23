package com.ject.module.auth.usecase

import com.ject.support.IntegrationTestSupport
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.convention.TestBean
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DeleteExpiredLoginHistoriesUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var deleteExpiredLoginHistoriesUseCase: DeleteExpiredLoginHistoriesUseCase

    @TestBean(methodName = "fixedClock")
    private lateinit var clock: Clock

    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM login_history WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        test("5월 29일에는 한국 시간으로 2월 28일의 보관 경계보다 앞선 기록만 지운다") {
            // given
            val memberId = createMember()
            insertHistory(memberId, Instant.parse("2026-02-28T03:29:59.999999+09:00"))
            insertHistory(memberId, Instant.parse("2026-02-28T03:30:00+09:00"))
            insertHistory(memberId, Instant.parse("2026-03-01T01:00:00+09:00"))

            // when
            deleteExpiredLoginHistoriesUseCase.delete()

            // then
            val remaining =
                jdbc.queryForList("SELECT occurred_at FROM login_history WHERE member_id = ?", Timestamp::class.java, memberId)
            remaining.map { checkNotNull(it).toInstant() } shouldContainExactlyInAnyOrder
                listOf(Instant.parse("2026-02-28T03:30:00+09:00"), Instant.parse("2026-03-01T01:00:00+09:00"))
        }
    }

    private fun insertHistory(
        memberId: Long,
        occurredAt: Instant,
    ) {
        jdbc.update(
            "INSERT INTO login_history (member_id, login_type, ip_address, occurred_at) VALUES (?, 'LOGIN', '203.0.113.7', ?)",
            memberId,
            Timestamp.from(occurredAt),
        )
    }

    private fun createMember(): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) " +
                        "VALUES ('GOOGLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(id)
        return id
    }

    companion object {
        @JvmStatic
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-05-29T03:30:00+09:00"), ZoneOffset.UTC)
    }
}
