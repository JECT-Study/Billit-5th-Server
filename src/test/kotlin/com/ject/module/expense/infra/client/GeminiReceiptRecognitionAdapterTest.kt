package com.ject.module.expense.infra.client

import com.ject.external.gemini.GeminiClient
import com.ject.external.gemini.GeminiProperties
import com.ject.global.error.BusinessException
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ReceiptRecognitionResult
import com.ject.support.GeminiServerFixture
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate

class GeminiReceiptRecognitionAdapterTest :
    FunSpec({
        val jsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
        lateinit var server: GeminiServerFixture
        lateinit var adapter: GeminiReceiptRecognitionAdapter

        /** 모델이 [receiptJson]을 생성한 것처럼 응답하게 합니다. */
        fun generate(receiptJson: String) = server.respond(200, server.generatedResponse(listOf(mapOf("text" to receiptJson))))

        beforeTest {
            server = GeminiServerFixture()
            adapter =
                GeminiReceiptRecognitionAdapter(
                    GeminiClient(
                        GeminiProperties("test-api-key", "test-model", server.baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                    ),
                    jsonMapper,
                )
        }

        afterTest { server.close() }

        test("응답 스키마에는 인식 결과의 값과 영수증 여부만 넣고, 생성한 JSON을 인식 결과로 바꾼다") {
            // given
            generate(
                """
                {"isReceipt": true, "name": "Sushi Bar", "paidDate": "2026-11-05", "currency": "JPY", "originalAmount": 1200,
                 "discountAmount": 100.5, "items": [{"name": "Sushi", "amount": 1000, "discountAmount": null},
                 {"name": "Tea", "amount": 200.25, "discountAmount": 0}]}
                """.trimIndent(),
            )

            // when
            val result = adapter.recognize(image, "image/jpeg")

            // then
            val request = jsonMapper.readValue(server.requestBody, Map::class.java)
            val responseFormat = (request["generationConfig"] as Map<*, *>)["responseFormat"] as Map<*, *>
            val schema = (responseFormat["text"] as Map<*, *>)["schema"] as Map<*, *>
            val itemSchema = ((schema["properties"] as Map<*, *>)["items"] as Map<*, *>)["items"] as Map<*, *>
            assertSoftly {
                (schema["properties"] as Map<*, *>).keys shouldBe
                    setOf("isReceipt", "name", "paidDate", "currency", "originalAmount", "discountAmount", "items")
                (itemSchema["properties"] as Map<*, *>).keys shouldBe setOf("name", "amount", "discountAmount")
                result shouldBe
                    ReceiptRecognitionResult(
                        name = "Sushi Bar",
                        paidDate = LocalDate.parse("2026-11-05"),
                        currency = ExpenseCurrency.JPY,
                        originalAmount = BigDecimal("1200"),
                        discountAmount = BigDecimal("100.5"),
                        items =
                            listOf(
                                ReceiptRecognitionResult.Item("Sushi", BigDecimal("1000"), null),
                                ReceiptRecognitionResult.Item("Tea", BigDecimal("200.25"), BigDecimal("0")),
                            ),
                    )
            }
        }

        test("지출 통화가 아닌 통화, 날짜가 아닌 결제 날짜와 빈 이름은 null로 바꾼다") {
            // given
            generate(
                """
                {"isReceipt": true, "name": " ", "paidDate": "2026-13-45", "currency": "THB", "originalAmount": null,
                 "discountAmount": null, "items": [{"name": "", "amount": null, "discountAmount": null}]}
                """.trimIndent(),
            )

            // when
            val result = adapter.recognize(image, "image/jpeg")

            // then
            result shouldBe ReceiptRecognitionResult(null, null, null, null, null, listOf(ReceiptRecognitionResult.Item(null, null, null)))
        }

        test("모델이 영수증이 아니라고 판단하면 400 EXPENSE_NOT_RECEIPT로 실패한다") {
            // given
            generate(
                """{"isReceipt": false, "name": null, "paidDate": null, "currency": null, "originalAmount": null, "discountAmount": null, "items": []}""",
            )

            // when
            val failure = shouldThrow<BusinessException> { adapter.recognize(image, "image/jpeg") }

            // then
            failure.errorCode shouldBe ExpenseErrorCode.NOT_RECEIPT
        }

        test("Gemini 호출 실패나 해석할 수 없는 생성 결과는 빈 결과 대신 503 EXPENSE_RECOGNITION_UNAVAILABLE로 실패한다") {
            // given
            val responses =
                mapOf(
                    "잘못된 키" to Pair(400, """{"error":{"code":400}}"""),
                    "요청 한도 초과" to Pair(429, """{"error":{"code":429}}"""),
                    "JSON이 아닌 생성 결과" to Pair(200, server.generatedResponse(listOf(mapOf("text" to "영수증입니다.")))),
                    "영수증 여부가 없는 생성 결과" to Pair(200, server.generatedResponse(listOf(mapOf("text" to """{"name": "Cafe", "items": []}""")))),
                )

            // when
            val failures =
                responses.mapValues { (_, response) ->
                    server.respond(response.first, response.second)
                    shouldThrow<BusinessException> { adapter.recognize(image, "image/jpeg") }.errorCode
                }

            // then
            failures shouldBe responses.mapValues { ExpenseErrorCode.RECOGNITION_UNAVAILABLE }
        }
    })
