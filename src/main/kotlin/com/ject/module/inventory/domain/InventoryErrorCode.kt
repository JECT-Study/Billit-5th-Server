package com.ject.module.inventory.domain

import com.ject.global.error.ErrorCode

enum class InventoryErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    STOCK_NOT_FOUND(404, "재고를 찾을 수 없습니다."),
    INVALID_QUANTITY(400, "예약 수량은 1 이상이어야 합니다."),
    INSUFFICIENT_STOCK(409, "예약할 수 있는 재고가 부족합니다."),
    ;

    override val code: String = "INVENTORY_$name"
}
