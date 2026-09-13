package com.duri.rentalplatform.domain.risk.vo;

import java.math.BigDecimal;

/**
 * 깡통전세 판정(RISK-02) 결과.
 *
 * @param seniorDebtTotal 유효한 을구의 채권최고액 + 선순위 임차보증금 합계(원)
 * @param debtRatio       전세가율(%) — (선순위채권 합계 + 보증금) ÷ 시세 × 100, 소수 둘째 자리 HALF_UP
 * @param negativeEquity  깡통전세인가. 반올림 전 값으로 판정하므로 {@code debtRatio} 가 기준 비율과 같아도 참일 수 있다
 */
public record NegativeEquityResult(
        long seniorDebtTotal,
        BigDecimal debtRatio,
        boolean negativeEquity
) {
}
