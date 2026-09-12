package com.duri.rentalplatform.domain.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 계약 유형. {@code property_code} 의 {@code CONTRACT_TYPE} 그룹 값과 같다.
 *
 * <p>국내 제도 고유 개념이라 음차가 아니라 특성을 나타내는 영어로 명명한다 — 규약 문서 「네이밍」.
 */
@Getter
@RequiredArgsConstructor
public enum ContractType {
    DEPOSIT_ONLY("전세"),
    MONTHLY_RENT("월세"),
    SEMI_DEPOSIT("반전세");

    private final String label;
}
