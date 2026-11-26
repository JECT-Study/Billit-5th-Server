package com.ject.module.expense.presentation

/**
 * API의 분배 방식입니다. `EQUAL`은 전체 1/N, `BY_ITEM`은 품목별 분배입니다.
 *
 * DB enum `split_type`은 품목별 분배를 `ITEM`으로 저장하므로, 상수 이름이 API 값인 이 enum을 따로 두고 Controller에서 [com.ject.module.expense.domain.SplitType]과 바꿉니다.
 */
enum class ExpenseSplitType {
    EQUAL,
    BY_ITEM,
}
