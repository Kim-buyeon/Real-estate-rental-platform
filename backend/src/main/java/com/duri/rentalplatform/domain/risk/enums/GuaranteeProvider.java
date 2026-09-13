package com.duri.rentalplatform.domain.risk.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 보증기관. {@code guarantee_criteria.provider} 에 저장하고 위험도 응답 {@code providers[].provider} 로 나간다 —
 * API 명세서(위험도 분석) 1.1.
 */
@Getter
@RequiredArgsConstructor
public enum GuaranteeProvider {
    HUG("주택도시보증공사"),
    HF("한국주택금융공사"),
    SGI("서울보증보험");

    private final String label;
}
