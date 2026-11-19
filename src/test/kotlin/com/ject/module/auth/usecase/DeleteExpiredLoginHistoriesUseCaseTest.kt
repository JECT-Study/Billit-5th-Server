package com.ject.module.auth.usecase

import com.ject.support.IntegrationTestSupport
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class DeleteExpiredLoginHistoriesUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var deleteExpiredLoginHistoriesUseCase: DeleteExpiredLoginHistoriesUseCase

    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM login_history WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        test("3개월이 지난 로그인 기록만 지우고 3개월 안의 기록은 남긴다") {
            // given
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val cutoff = now.atZone(ZoneOffset.UTC).minusMonths(3).toInstant()
            val memberId = createMember()
            insertHistory(memberId, cutoff.minus(Duration.ofHours(1)))
            insertHistory(memberId, cutoff.plus(Duration.ofHours(1)))
            insertHistory(memberId, now.minus(Duration.ofDays(1)))

            // when
            deleteExpiredLoginHistoriesUseCase.delete()

            // then
            val remaining =
                jdbc.queryForList("SELECT occurred_at FROM login_history WHERE member_id = ?", Timestamp::class.java, memberId)
            remaining.map { checkNotNull(it).toInstant() } shouldContainExactlyInAnyOrder
                listOf(cutoff.plus(Duration.ofHours(1)), now.minus(Duration.ofDays(1)))
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
}
