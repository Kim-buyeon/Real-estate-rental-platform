package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.enums.GradeReason;

/**
 * 위험 등급 판정(RISK-01) 결과.
 *
 * @param riskGrade   등급
 * @param gradeReason 등급을 정한 분기
 */
public record RiskGradeResult(
        RiskGrade riskGrade,
        GradeReason gradeReason
) {
}
