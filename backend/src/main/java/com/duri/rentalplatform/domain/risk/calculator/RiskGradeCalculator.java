package com.duri.rentalplatform.domain.risk.calculator;

import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.enums.GradeReason;
import com.duri.rentalplatform.domain.risk.vo.RiskGradeResult;
import java.math.BigDecimal;

/**
 * 위험 등급을 판정한다(RISK-01) — business-logic.md 3장.
 *
 * <p>분기 순서는 {@link GradeReason} 선언 순서다.
 * <ol>
 *   <li>깡통전세(RISK-02) → DANGER · NEGATIVE_EQUITY. 가입 가능이어도 DANGER 이고, 가입 불가와 겹치면 이 사유가 앞선다.</li>
 *   <li>3사 모두 가입 불가(RISK-05) → DANGER · INSURANCE_INELIGIBLE</li>
 *   <li>위험금액 × 100 &gt; 시세 × CAUTION 경계 → CAUTION · LEASE_RATIO_CAUTION. <b>초과</b>만 CAUTION 이다.</li>
 *   <li>그 외 → SAFE · INSURANCE_ELIGIBLE</li>
 * </ol>
 *
 * <p>전세가율 비교는 RISK-02 · 05 와 같이 반올림 없는 정수 교차곱으로 한다 — 반올림한 비율로 비교하면 경계 +1원이
 * 경계값으로 반올림돼 빠진다. 경계값은 {@code risk_criteria.caution_lease_ratio} 를 호출부가 넘긴다.
 */
public final class RiskGradeCalculator {

    /** 백분율 환산 계수. 임계값이 아니다. */
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    /**
     * @param negativeEquity    깡통전세인가 — RISK-02 {@code negativeEquity}
     * @param insuranceEligible 3사 중 하나 이상 가입 가능 — RISK-05 {@code insuranceEligible}
     * @param riskAmount        위험금액(원) — 선순위채권 합계 + 보증금
     * @param marketPrice       시세(원). 0 이하이면 판정할 수 없다
     * @param cautionLeaseRatio SAFE/CAUTION 경계(%) — {@code risk_criteria.caution_lease_ratio}
     * @throws IllegalArgumentException 시세가 0 이하
     */
    public static RiskGradeResult calculate(boolean negativeEquity, boolean insuranceEligible, long riskAmount,
            long marketPrice, BigDecimal cautionLeaseRatio) {
        if (marketPrice <= 0) {
            throw new IllegalArgumentException("시세가 없어 위험 등급을 판정할 수 없다: " + marketPrice);
        }
        if (negativeEquity) {
            return new RiskGradeResult(RiskGrade.DANGER, GradeReason.NEGATIVE_EQUITY);
        }
        if (!insuranceEligible) {
            return new RiskGradeResult(RiskGrade.DANGER, GradeReason.INSURANCE_INELIGIBLE);
        }
        BigDecimal riskAmountPercent = BigDecimal.valueOf(riskAmount).multiply(PERCENT);
        if (riskAmountPercent.compareTo(BigDecimal.valueOf(marketPrice).multiply(cautionLeaseRatio)) > 0) {
            return new RiskGradeResult(RiskGrade.CAUTION, GradeReason.LEASE_RATIO_CAUTION);
        }
        return new RiskGradeResult(RiskGrade.SAFE, GradeReason.INSURANCE_ELIGIBLE);
    }

    private RiskGradeCalculator() {
    }
}
