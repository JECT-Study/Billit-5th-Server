package com.ject.module.expense.presentation

import com.ject.global.error.ErrorResponse
import com.ject.global.response.ApiResponse
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.SplitType
import com.ject.module.expense.presentation.request.RecognizeReceiptRequest
import com.ject.module.expense.presentation.response.ReceiptRecognitionResponse
import com.ject.module.expense.usecase.RecognizeReceiptUseCase
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartFile
import java.math.RoundingMode

/** 영수증 인식은 지출 자원이 아닌 별도 경로와 multipart 요청, 업로드 크기 오류를 다루므로 지출 Controller와 나눕니다. */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/receipt-recognitions")
class ReceiptRecognitionController(
    private val recognizeReceiptUseCase: RecognizeReceiptUseCase,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun recognizeReceipt(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable tripId: Long,
        @RequestPart("image") image: MultipartFile,
        @RequestPart("request") request: RecognizeReceiptRequest,
    ): ResponseEntity<ApiResponse<ReceiptRecognitionResponse>> {
        val splitType =
            when (request.splitType) {
                ExpenseSplitType.EQUAL -> SplitType.EQUAL
                ExpenseSplitType.BY_ITEM -> SplitType.ITEM
            }
        val receipt = recognizeReceiptUseCase.recognize(memberId(accessToken), tripId, splitType, image.bytes, image.contentType)

        return ResponseEntity.ok(
            ApiResponse(
                ReceiptRecognitionResponse(
                    name = receipt.name,
                    paidDate = receipt.paidDate,
                    currency = receipt.currency,
                    originalAmount = receipt.originalAmount?.setScale(2, RoundingMode.UNNECESSARY)?.toPlainString(),
                    discountAmount = receipt.discountAmount?.setScale(2, RoundingMode.UNNECESSARY)?.toPlainString(),
                    items =
                        receipt.items.map {
                            ReceiptRecognitionResponse.Item(
                                name = it.name,
                                amount = it.amount?.setScale(2, RoundingMode.UNNECESSARY)?.toPlainString(),
                                discountAmount = it.discountAmount?.setScale(2, RoundingMode.UNNECESSARY)?.toPlainString(),
                            )
                        },
                ),
            ),
        )
    }

    /** multipart를 늦게 읽도록(`resolve-lazily`) 설정해, 업로드 상한을 넘으면 이 Controller의 인자를 읽을 때 이 예외가 납니다. */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleReceiptTooLarge(): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(ExpenseErrorCode.RECEIPT_TOO_LARGE.status).body(ErrorResponse.from(ExpenseErrorCode.RECEIPT_TOO_LARGE))

    private fun memberId(accessToken: Jwt): Long = checkNotNull(accessToken.subject).toLong()
}
