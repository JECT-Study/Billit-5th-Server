package com.ject.module.expense.infra.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.ject.external.gemini.GeminiClient
import com.ject.external.gemini.GeminiException
import com.ject.external.gemini.GeminiFailureReason
import com.ject.global.error.BusinessException
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ReceiptRecognitionResult
import com.ject.module.expense.usecase.port.ReceiptRecognitionPort
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 영수증 이미지를 Gemini로 인식합니다. 지시문과 응답 JSON 스키마는 이 Adapter에만 둡니다.
 *
 * - 스키마에는 [ReceiptRecognitionResult]에 담을 값과 영수증 여부만 넣습니다.
 * - 응답의 통화가 지출 통화가 아니거나 날짜를 읽을 수 없으면 `null`로 바꿉니다. 금액의 범위와 자릿수는 UseCase가 확인합니다.
 * - 이미지, 인식 원문과 Gemini 오류 본문은 로그에 남기지 않습니다. 실패 사유와 HTTP 상태만 남깁니다.
 */
class GeminiReceiptRecognitionAdapter(
    private val geminiClient: GeminiClient,
    private val jsonMapper: JsonMapper,
) : ReceiptRecognitionPort {
    override fun recognize(
        image: ByteArray,
        mediaType: String,
    ): ReceiptRecognitionResult {
        val receipt =
            try {
                jsonMapper.readValue(geminiClient.generateJson(PROMPT, image, mediaType, RESPONSE_SCHEMA), GeminiReceipt::class.java)
            } catch (exception: GeminiException) {
                logUnavailable(exception.reason.name, exception.statusCode)
                throw BusinessException(ExpenseErrorCode.RECOGNITION_UNAVAILABLE, exception)
            } catch (exception: JacksonException) {
                logUnavailable("INVALID_JSON", null)
                throw BusinessException(ExpenseErrorCode.RECOGNITION_UNAVAILABLE, exception)
            }
        if (!receipt.isReceipt) {
            throw BusinessException(ExpenseErrorCode.NOT_RECEIPT)
        }

        return ReceiptRecognitionResult(
            name = receipt.name?.takeIf { it.isNotBlank() },
            paidDate =
                try {
                    receipt.paidDate?.let(LocalDate::parse)
                } catch (_: DateTimeParseException) {
                    null
                },
            currency = ExpenseCurrency.entries.find { it.name == receipt.currency },
            originalAmount = receipt.originalAmount,
            discountAmount = receipt.discountAmount,
            items =
                receipt.items.orEmpty().map {
                    ReceiptRecognitionResult.Item(
                        name =
                            it.name?.takeIf { name ->
                                name.isNotBlank()
                            },
                        amount = it.amount,
                        discountAmount = it.discountAmount,
                    )
                },
        )
    }

    private fun logUnavailable(
        reason: String,
        status: Int?,
    ) {
        // 4xx(요청 한도 초과 제외)는 키, 모델 코드나 요청 형식 문제일 수 있어 운영자가 확인하도록 ERROR로 남깁니다.
        if (reason == GeminiFailureReason.HTTP_ERROR.name && status != null && status in 400..499 && status != TOO_MANY_REQUESTS) {
            log.error { "event=receipt_recognition_unavailable provider=GEMINI reason=$reason status=$status" }
        } else {
            log.warn { "event=receipt_recognition_unavailable provider=GEMINI reason=$reason status=$status" }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GeminiReceipt(
        val isReceipt: Boolean,
        val name: String?,
        val paidDate: String?,
        val currency: String?,
        val originalAmount: BigDecimal?,
        val discountAmount: BigDecimal?,
        val items: List<GeminiItem>?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GeminiItem(
        val name: String?,
        val amount: BigDecimal?,
        val discountAmount: BigDecimal?,
    )

    companion object {
        private val log = KotlinLogging.logger {}

        private const val TOO_MANY_REQUESTS = 429

        private const val PROMPT =
            "이 이미지가 결제 영수증인지 판단하고, 영수증이면 지출 입력에 필요한 값을 응답 스키마에 맞춰 읽어 주세요. " +
                "영수증에 적힌 값만 읽고, 읽을 수 없거나 적혀 있지 않은 값은 추측하지 말고 null로 두세요. " +
                "금액은 통화 기호와 천 단위 구분자 없이 숫자로 적어 주세요."

        private val NULLABLE_STRING = listOf("string", "null")
        private val NULLABLE_NUMBER = listOf("number", "null")

        private val RESPONSE_SCHEMA: Map<String, Any> =
            mapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "isReceipt" to mapOf("type" to "boolean", "description" to "결제 영수증이나 계산서 사진이면 true, 그 밖의 이미지면 false입니다."),
                        "name" to mapOf("type" to NULLABLE_STRING, "description" to "가맹점이나 상호 이름입니다. 영수증에 적힌 언어 그대로 적습니다."),
                        "paidDate" to
                            mapOf("type" to NULLABLE_STRING, "format" to "date", "description" to "결제 날짜(YYYY-MM-DD)입니다. 시각은 적지 않습니다."),
                        "currency" to mapOf("type" to NULLABLE_STRING, "description" to "결제 통화의 ISO 4217 코드(예: KRW, JPY, USD)입니다."),
                        "originalAmount" to mapOf("type" to NULLABLE_NUMBER, "description" to "할인을 빼기 전 결제 총액입니다. 세금과 봉사료를 포함합니다."),
                        "discountAmount" to mapOf("type" to NULLABLE_NUMBER, "description" to "특정 품목이 아니라 결제 전체에 적용한 할인액의 합(양수)입니다."),
                        "items" to
                            mapOf(
                                "type" to "array",
                                "description" to "구매한 품목입니다. 세금이나 봉사료가 따로 적혀 있으면 품목으로 넣습니다. 품목이 없으면 빈 배열입니다.",
                                "items" to
                                    mapOf(
                                        "type" to "object",
                                        "properties" to
                                            mapOf(
                                                "name" to mapOf("type" to NULLABLE_STRING, "description" to "품목 이름입니다."),
                                                "amount" to
                                                    mapOf("type" to NULLABLE_NUMBER, "description" to "상품 할인을 빼기 전 품목 금액(수량 반영)입니다."),
                                                "discountAmount" to
                                                    mapOf("type" to NULLABLE_NUMBER, "description" to "이 품목에만 적용한 할인액(양수)입니다."),
                                            ),
                                        "required" to listOf("name", "amount", "discountAmount"),
                                    ),
                            ),
                    ),
                "required" to listOf("isReceipt", "name", "paidDate", "currency", "originalAmount", "discountAmount", "items"),
            )
    }
}
