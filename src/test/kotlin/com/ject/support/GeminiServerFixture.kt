package com.ject.support

import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Gemini API `generateContent` 대신 응답하는 로컬 서버입니다. 실제 Gemini를 호출하지 않습니다.
 *
 * 마지막 요청의 경로, API 키와 본문을 기록하고, [respond]로 정한 상태 코드와 본문으로 응답합니다. 사용 후 [close]로 서버를 닫습니다.
 */
class GeminiServerFixture : AutoCloseable {
    @Volatile
    var requestPath: String? = null

    @Volatile
    var apiKey: String? = null

    @Volatile
    var requestBody: String = ""

    @Volatile
    private var response = Response(200, "{}", Duration.ZERO)

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requestPath = exchange.requestURI.path
                apiKey = exchange.requestHeaders.getFirst("x-goog-api-key")
                requestBody = exchange.requestBody.use { String(it.readAllBytes()) }
                val current = response
                Thread.sleep(current.delay.toMillis())
                val bytes = current.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(current.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            // 응답을 늦추는 요청이 다음 요청을 막지 않도록 요청마다 스레드를 씁니다.
            executor = Executors.newCachedThreadPool()
            start()
        }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    fun respond(
        status: Int,
        body: String,
        delay: Duration = Duration.ZERO,
    ) {
        response = Response(status, body, delay)
    }

    /** [parts]를 첫 후보의 내용으로 담은 generateContent 응답 본문입니다. 각 원소는 `text`와 선택적인 `thought`를 가진 파트입니다. */
    fun generatedResponse(
        parts: List<Map<String, Any>>,
        finishReason: String = "STOP",
    ): String =
        JsonMapper.builder().build().writeValueAsString(
            mapOf(
                "candidates" to listOf(mapOf("content" to mapOf("role" to "model", "parts" to parts), "finishReason" to finishReason)),
                "usageMetadata" to mapOf("totalTokenCount" to 10),
            ),
        )

    override fun close() {
        server.stop(0)
    }

    private data class Response(
        val status: Int,
        val body: String,
        val delay: Duration,
    )
}
