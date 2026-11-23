package com.ject.module.auth.implement

import com.ject.support.IntegrationTestSupport
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginHistoryCreatorTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var authTokenCreator: AuthTokenCreator

    private val httpClient = HttpClient.newHttpClient()
    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM login_history WHERE member_id = ?", id)
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        test("Nginx가 전달한 X-Forwarded-For의 오른쪽 외부 주소를 기록하고 클라이언트가 앞에 넣은 주소는 쓰지 않는다") {
            // given
            val memberId = createMember()
            val refreshToken = authTokenCreator.createMemberTokens(memberId).refreshToken
            val request =
                HttpRequest
                    .newBuilder(
                        URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}/api/v1/auth/token-refreshes"),
                    ).header("Content-Type", "application/json")
                    .header("X-Forwarded-For", "198.51.100.9, 203.0.113.7")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"refreshToken":"$refreshToken"}"""))
                    .build()

            // when
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            // then
            response.statusCode() shouldBe 200
            jdbc.queryForObject(
                "SELECT ip_address FROM login_history WHERE member_id = ? AND login_type = 'TOKEN_REFRESH'",
                String::class.java,
                memberId,
            ) shouldBe "203.0.113.7"
        }
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
