package com.ject.external.oauth.kakao

import com.ject.external.oauth.OAuthClientException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class KakaoOAuthClientTest :
    FunSpec({
        lateinit var server: MockRestServiceServer
        lateinit var client: KakaoOAuthClient

        beforeTest {
            val builder = RestClient.builder().baseUrl("https://provider.example")
            server = MockRestServiceServer.bindTo(builder).build()
            client =
                KakaoOAuthClient(builder.build(), KakaoOAuthProperties("client", "secret", "https://app.example/callback"), builder.build())
        }

        afterTest { server.verify() }

        test("인가 코드를 교환하고 제공자 사용자 식별자를 반환한다") {
            // given
            server
                .expect(requestTo("https://provider.example/oauth/token"))
                .andExpect { request ->
                    val body = (request as MockClientHttpRequest).bodyAsString
                    body shouldContain "grant_type=authorization_code"
                    body shouldContain "client_id=client"
                    body shouldContain "code=code"
                    body shouldContain "client_secret=secret"
                }.andRespond(withSuccess("""{"access_token":"provider-access","extra":"ignored"}""", MediaType.APPLICATION_JSON))
            server
                .expect(requestTo("https://provider.example/v2/user/me"))
                .andExpect { request -> request.headers.getFirst("Authorization") shouldBe "Bearer provider-access" }
                .andRespond(withSuccess("""{"id":123,"extra":"ignored"}""", MediaType.APPLICATION_JSON))

            // when
            val identity = client.fetchIdentity("code")

            // then
            identity.providerUserId shouldBe "123"
        }

        test("만료되거나 재사용한 코드는 인증 거절로 구분하고 응답 원문을 노출하지 않는다") {
            // given
            server
                .expect(requestTo("https://provider.example/oauth/token"))
                .andRespond(
                    withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""{"error":"invalid_grant","error_description":"sensitive"}"""),
                )

            // when
            val failure = shouldThrow<OAuthClientException> { client.fetchIdentity("code") }

            // then
            failure.reason shouldBe OAuthClientException.Reason.INVALID_AUTHORIZATION
            failure.statusCode shouldBe 400
            failure.cause shouldBe null
            failure.message?.contains("sensitive") shouldBe false
        }

        test("제공자 장애는 잘못된 인가 코드와 구분한다") {
            // given
            server
                .expect(requestTo("https://provider.example/oauth/token"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

            // when
            val failure = shouldThrow<OAuthClientException> { client.fetchIdentity("code") }

            // then
            failure.reason shouldBe OAuthClientException.Reason.UNAVAILABLE
        }
    })
