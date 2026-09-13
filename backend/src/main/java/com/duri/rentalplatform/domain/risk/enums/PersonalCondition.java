package com.duri.rentalplatform.domain.risk.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 시스템이 판정하지 않는 개인 자격 확인 사항. 위험도 응답 {@code personalConditions} 로 나간다 — API 명세서(위험도 분석)
 * 1.1, 비즈니스 로직 정의서 2장 「가입 시 추가 확인 사항」.
 */
@Getter
@RequiredArgsConstructor
public enum PersonalCondition {
    ANNUAL_INCOME("연소득 기준"),
    APPLICATION_DEADLINE("신청기한"),
    /** 대항력 요건. 계약 후 임차인이 하는 행위라 매물 데이터로 판정할 수 없다. */
    MOVE_IN_AND_FIXED_DATE("전입신고 · 확정일자");

    private final String label;
}
