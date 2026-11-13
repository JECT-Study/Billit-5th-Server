package com.ject.support

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 애플 토큰 API(`/auth/token`, `/auth/revoke`) 대신 응답하는 로컬 서버입니다. 실제 애플을 호출하지 않습니다.
 *
 * 받은 요청의 폼 값을 [requests]에 기록하고, [respond]로 정한 상태 코드와 본문으로 응답합니다. 사용 후 [close]로 서버를 닫습니다.
 */
class AppleAuthServerFixture : AutoCloseable {
    /** client secret 서명용 테스트 키입니다. 공개키로 client secret을 검증합니다. */
    val clientSecretKey: ECKey = ECKeyGenerator(Curve.P_256).keyID("test-apple-key-id").generate()

    /** [clientSecretKey]의 개인키를 `.p8` PEM으로 만든 뒤 base64로 인코딩한 값입니다. `APPLE_PRIVATE_KEY_BASE64` 형식과 같습니다. */
    val privateKeyBase64: String =
        Base64.getEncoder().encodeToString(
            (
                "-----BEGIN PRIVATE KEY-----\n" +
                    Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(clientSecretKey.toECPrivateKey().encoded) +
                    "\n-----END PRIVATE KEY-----\n"
            ).toByteArray(),
        )

    val requests = CopyOnWriteArrayList<Request>()

    @Volatile
    private var response = Response(200, "{}", Duration.ZERO)

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.use { String(it.readAllBytes()) }
                requests.add(Request(exchange.requestURI.path, exchange.requestHeaders.getFirst("Content-Type"), parseForm(body)))
                val current = response
                Thread.sleep(current.delay.toMillis())
                val bytes = current.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(current.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    val tokenUri: String = "http://127.0.0.1:${server.address.port}/auth/token"
    val revokeUri: String = "http://127.0.0.1:${server.address.port}/auth/revoke"

    fun respond(
        status: Int,
        body: String,
        delay: Duration = Duration.ZERO,
    ) {
        response = Response(status, body, delay)
    }

    /** 인가 코드 교환 성공 응답 본문입니다. [idToken]이 `null`이면 응답에 넣지 않습니다. */
    fun tokenResponse(
        refreshToken: String,
        idToken: String?,
    ): String {
        val idTokenField = idToken?.let { ""","id_token":"$it"""" }.orEmpty()
        return """{"access_token":"apple-access-token","token_type":"Bearer","expires_in":3600,""" +
            """"refresh_token":"$refreshToken"$idTokenField}"""
    }

    override fun close() {
        server.stop(0)
    }

    private fun parseForm(body: String): Map<String, String> =
        body
            .split("&")
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val (name, value) = pair.split("=", limit = 2)
                URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }

    data class Request(
        val path: String,
        val contentType: String?,
        val form: Map<String, String>,
    )

    private data class Response(
        val status: Int,
        val body: String,
        val delay: Duration,
    )
}
