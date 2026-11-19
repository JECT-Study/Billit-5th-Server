package com.ject.module.auth.usecase

import com.ject.support.IntegrationTestSupport
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

class DeleteExpiredLoginHistoriesUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var deleteExpiredLoginHistoriesUseCase: DeleteExpiredLoginHistoriesUseCase

    @MockitoBean
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

        test("3개월이 지난 로그인 기록만 지우고 정확히 3개월 전과 그 이후의 기록은 남긴다") {
            // given
            `when`(clock.instant()).thenReturn(Instant.parse("2026-11-19T00:00:00Z"))
            val memberId = createMember()
            insertHistory(memberId, Instant.parse("2026-08-18T23:59:59Z"))
            insertHistory(memberId, Instant.parse("2026-08-19T00:00:00Z"))
            insertHistory(memberId, Instant.parse("2026-11-18T00:00:00Z"))

            // when
            deleteExpiredLoginHistoriesUseCase.delete()

            // then
            val remaining =
                jdbc.queryForList("SELECT occurred_at FROM login_history WHERE member_id = ?", Timestamp::class.java, memberId)
            remaining.map { checkNotNull(it).toInstant() } shouldContainExactlyInAnyOrder
                listOf(Instant.parse("2026-08-19T00:00:00Z"), Instant.parse("2026-11-18T00:00:00Z"))
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
