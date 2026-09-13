package com.duri.rentalplatform.domain.admin.enums;

/**
 * 변경 이력의 대상 기준 테이블. {@code criteria_change_history.target_table} 에 저장하고 이력 응답 {@code target} 으로
 * 나간다 — API 명세서(관리자) 1.2.
 */
public enum CriteriaTarget {
    GUARANTEE_CRITERIA,
    HUG_CRITERIA,
    HF_CRITERIA,
    SGI_CRITERIA,
    GUARANTEE_PREMIUM_RATE,
    LOAN_REGULATION,
    RISK_CRITERIA
}
