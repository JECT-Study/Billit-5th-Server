package com.ject.external.gemini

import com.ject.support.GeminiServerFixture
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.time.Duration
import java.util.Base64

class GeminiClientTest :
    FunSpec({
        val jsonMapper = JsonMapper.builder().build()
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01)
        val schema = mapOf("type" to "object", "properties" to mapOf("total" to mapOf("type" to listOf("number", "null"))))
        lateinit var server: GeminiServerFixture

        fun client(
            baseUrl: String = server.baseUrl,
            readTimeout: Duration = Duration.ofSeconds(5),
        ) = GeminiClient(
            GeminiProperties(
                apiKey = "test-api-key",
                model = "test-model",
                baseUrl = baseUrl,
                connectTimeout = Duration.ofSeconds(2),
                readTimeout = readTimeout,
            ),
        )

        beforeTest { server = GeminiServerFixture() }

        afterTest { server.close() }

        test("API 키 헤더, base64 inline 이미지와 응답 JSON 스키마를 모델 경로로 보내고 생성을 마친 첫 후보의 텍스트를 사고 과정 없이 반환한다") {
            // given
            server.respond(
                200,
                server.generatedResponse(
                    listOf(mapOf("text" to "이미지를 살펴봅니다.", "thought" to true), mapOf("text" to """{"total":"""), mapOf("text" to "12.5}")),
                ),
            )

            // when
            val text = client().generateJson("영수증을 읽어 주세요.", image, "image/png", schema)

            // then
            val request = jsonMapper.readValue(server.requestBody, Map::class.java)
            val parts = ((request["contents"] as List<*>).single() as Map<*, *>)["parts"] as List<*>
            val inlineData = (parts[0] as Map<*, *>)["inlineData"] as Map<*, *>
            assertSoftly {
                text shouldBe """{"total":12.5}"""
                server.requestPath shouldBe "/v1beta/models/test-model:generateContent"
                server.apiKey shouldBe "test-api-key"
                inlineData["mimeType"] shouldBe "image/png"
                Base64.getDecoder().decode(inlineData["data"] as String).toList() shouldBe image.toList()
                parts[1] shouldBe mapOf("text" to "영수증을 읽어 주세요.")
                request["generationConfig"] shouldBe
                    mapOf("responseFormat" to mapOf("text" to mapOf("mimeType" to "APPLICATION_JSON", "schema" to schema)))
            }
        }

        test("오류 응답, 차단, 끝까지 마치지 못한 생성, 텍스트 없는 응답과 타임아웃은 사유를 담은 GeminiException으로 실패한다") {
            // given
            val responses =
                mapOf(
                    "잘못된 요청" to Triple(400, """{"error":{"code":400}}""", Duration.ZERO),
                    "서버 오류" to Triple(503, """{"error":{"code":503}}""", Duration.ZERO),
                    "차단된 요청" to Triple(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""", Duration.ZERO),
                    "토큰 한도로 중단된 생성" to Triple(200, server.generatedResponse(listOf(mapOf("text" to "{")), "MAX_TOKENS"), Duration.ZERO),
                    "사고 과정만 있는 응답" to
                        Triple(
                            200,
                            server.generatedResponse(listOf(mapOf("text" to "생각", "thought" to true))),
                            Duration.ZERO,
                        ),
                    "응답 타임아웃" to Triple(200, server.generatedResponse(listOf(mapOf("text" to "{}"))), Duration.ofSeconds(2)),
                )

            // when
            val failures =
                responses.mapValues { (_, response) ->
                    server.respond(response.first, response.second, response.third)
                    val exception =
                        shouldThrow<GeminiException> {
                            client(readTimeout = Duration.ofMillis(500)).generateJson("prompt", image, "image/png", schema)
                        }
                    exception.reason to exception.statusCode
                }

            // then
            failures shouldBe
                mapOf(
                    "잘못된 요청" to (GeminiFailureReason.HTTP_ERROR to 400),
                    "서버 오류" to (GeminiFailureReason.HTTP_ERROR to 503),
                    "차단된 요청" to (GeminiFailureReason.INVALID_RESPONSE to null),
                    "토큰 한도로 중단된 생성" to (GeminiFailureReason.INVALID_RESPONSE to null),
                    "사고 과정만 있는 응답" to (GeminiFailureReason.INVALID_RESPONSE to null),
                    "응답 타임아웃" to (GeminiFailureReason.TIMEOUT to null),
                )
        }

        test("연결할 수 없는 주소이면 CONNECTION 사유로 실패한다") {
            // given
            val closedPort = ServerSocket(0).use { it.localPort }

            // when
            val exception =
                shouldThrow<GeminiException> {
                    client(
                        baseUrl = "http://127.0.0.1:$closedPort",
                    ).generateJson("prompt", image, "image/png", schema)
                }

            // then
            exception.reason shouldBe GeminiFailureReason.CONNECTION
        }
    })
