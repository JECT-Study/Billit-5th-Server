package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ReceiptImageType
import com.ject.module.expense.domain.ReceiptRecognitionResult
import com.ject.module.expense.domain.SplitType
import com.ject.module.expense.usecase.port.ReceiptRecognitionPort
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 확정된 여행에 참여 중인 회원이 영수증 한 장에서 지출 입력값을 읽습니다. 인식만 하고 지출은 저장하지 않습니다.
 *
 * 외부 인식을 기다리는 동안 DB 트랜잭션을 열어 두지 않도록 트랜잭션 없이 실행합니다. 이미지는 메모리에서만 다루고 저장하거나 로그에 남기지 않습니다.
 */
@Service
class RecognizeReceiptUseCase(
    private val tripRepository: TripRepository,
    private val receiptRecognitionPort: ReceiptRecognitionPort,
) {
    /**
     * 1. 이미지가 JPEG, PNG, HEIC, WEBP 중 요청한 형식이 아니거나 내용이 그 형식이 아니면 415 `EXPENSE_RECEIPT_UNSUPPORTED`입니다.
     * 2. 여행이 없거나 삭제됐으면 404, 요청한 회원이 참여 중(`MEMBER`)이 아니면 403, 확정 전 여행이면 409 `EXPENSE_TRIP_NOT_CONFIRMED`입니다.
     * 3. 인식 실패는 [ReceiptRecognitionPort]를 따릅니다.
     * 4. 0 미만이거나 정수부가 10자리를 넘거나 소수점 이하가 2자리를 넘는 금액은 읽지 못한 값(`null`)으로 바꿉니다.
     * 5. 전체 1/N이면 품목을 비우고, 상품 할인을 전체 할인에 더합니다. 전체 1/N의 최종 결제액은 할인 전 총액에서 전체 할인만 빼기 때문입니다.
     */
    fun recognize(
        memberId: Long,
        tripId: Long,
        splitType: SplitType,
        image: ByteArray,
        mediaType: String?,
    ): ReceiptRecognitionResult {
        val imageType = ReceiptImageType.of(mediaType, image) ?: throw BusinessException(ExpenseErrorCode.RECEIPT_UNSUPPORTED)
        val tripStatus = tripRepository.findTripStatus(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        if (tripRepository.findParticipants(tripId).none { it.memberId == memberId && it.status == TripMemberStatus.MEMBER }) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }
        if (tripStatus != TripStatus.CONFIRMED) {
            throw BusinessException(ExpenseErrorCode.TRIP_NOT_CONFIRMED)
        }

        val recognized = receiptRecognitionPort.recognize(image, imageType.mediaType)
        val items =
            recognized.items.map {
                ReceiptRecognitionResult.Item(name = it.name, amount = amount(it.amount), discountAmount = amount(it.discountAmount))
            }
        val discountAmount = amount(recognized.discountAmount)

        return recognized.copy(
            originalAmount = amount(recognized.originalAmount),
            discountAmount =
                when (splitType) {
                    SplitType.ITEM -> {
                        discountAmount
                    }

                    SplitType.EQUAL -> {
                        (
                            listOf(
                                discountAmount,
                            ) + items.map { it.discountAmount }
                        ).filterNotNull().reduceOrNull(BigDecimal::add)
                    }
                },
            items = if (splitType == SplitType.ITEM) items else emptyList(),
        )
    }

    /** 지출 금액으로 받을 수 있는 값을 소수점 이하 2자리로 맞춥니다. 받을 수 없으면 `null`입니다. */
    private fun amount(value: BigDecimal?): BigDecimal? =
        value?.takeIf { it.signum() >= 0 && it < AMOUNT_LIMIT && it.stripTrailingZeros().scale() <= 2 }?.setScale(2)

    companion object {
        /** 지출 금액의 상한(미포함)입니다. 정수부는 10자리까지 받습니다. */
        private val AMOUNT_LIMIT = BigDecimal("10000000000")
    }
}
