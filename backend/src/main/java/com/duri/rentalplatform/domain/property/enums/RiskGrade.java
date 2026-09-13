package com.duri.rentalplatform.domain.property.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 전세사기 위험 등급. {@code risk_analysis.risk_grade} 에 상수명으로 저장되며 API 명세서(매물) 1.1
 * {@code riskGrade} 필터 · 1.4 마커 응답의 값과 같다.
 *
 * <p>매물 조회가 위험도 도메인보다 먼저 만들어져 여기에 둔다. <b>위험도 도메인이 생기면 옮긴다.</b>
 */
@Getter
@RequiredArgsConstructor
public enum RiskGrade {
    SAFE("안전"),
    CAUTION("주의"),
    DANGER("위험");

    private final String label;
}
