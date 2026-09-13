package com.duri.rentalplatform.domain.risk.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 보증보험 가입 판정(RISK-05)에서 위배된 집 단위 조건. 위험도 응답 {@code providers[].failedConditions} 로 나간다 —
 * API 명세서(위험도 분석) 1.1. 판정기가 이 선언 순서로 담는다.
 */
@Getter
@RequiredArgsConstructor
public enum GuaranteeFailedCondition {
    DEBT_RATIO_EXCEEDED("전세가율 초과"),
    SENIOR_DEBT_RATIO_EXCEEDED("선순위채권 한도 초과"),
    DEPOSIT_LIMIT_EXCEEDED("보증금 한도 초과"),
    VIOLATION_BUILDING("위반건축물"),
    RIGHT_VIOLATION("권리 침해"),
    OWNER_MISMATCH("명의 불일치"),
    ADDRESS_MISMATCH("주소 불일치");

    private final String label;
}
