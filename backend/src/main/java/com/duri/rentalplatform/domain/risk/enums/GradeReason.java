package com.duri.rentalplatform.domain.risk.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 위험 등급 결정 사유. 위험도 응답 {@code gradeReason} 로 나가고 {@code risk_analysis.risk_reason} 에 이름으로
 * 저장한다 — API 명세서(위험도 분석) 1.1, 비즈니스 로직 정의서 3장. 판정은 이 선언 순서로 분기한다.
 */
@Getter
@RequiredArgsConstructor
public enum GradeReason {
    NEGATIVE_EQUITY("깡통전세 해당"),
    INSURANCE_INELIGIBLE("3사 가입 불가"),
    LEASE_RATIO_CAUTION("전세가율 주의 구간"),
    INSURANCE_ELIGIBLE("보증보험 가입 가능");

    private final String label;
}
