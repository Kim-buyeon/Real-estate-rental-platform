package com.duri.rentalplatform.domain.risk.calculator;

import com.duri.rentalplatform.domain.risk.vo.MortgageEntry;
import com.duri.rentalplatform.domain.risk.vo.NegativeEquityResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 깡통전세를 판정한다(RISK-02).
 *
 * <p>말소된 을구({@code active = false})는 보지 않는다. 등기에 적힌 유효한 을구는 모두 신규 임차인보다 앞서므로
 * 순위를 비교하지 않고 채권최고액과 선순위 임차보증금(null 은 0)을 전부 더한다.
 *
 * <p>위험금액(선순위채권 합계 + 보증금)이 시세 × 기준 비율 ÷ 100 을 <b>초과</b>하면 깡통전세다. 비교는 반올림 없이
 * {@code 위험금액 × 100 > 시세 × 비율} 로 한다 — 반올림한 전세가율로 비교하면 경계 +1원이 기준값으로 반올림돼 뒤집힌다.
 * 기준 비율은 기준 테이블 값을 호출부가 넘긴다.
 */
public final class NegativeEquityCalculator {

    /** 백분율 환산 계수. 임계값이 아니다. */
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);
    private static final int RATIO_SCALE = 2;

    /**
     * @param entries             을구 목록
     * @param deposit             보증금(원)
     * @param marketPrice         시세(원). 0 이하이면 판정할 수 없다
     * @param negativeEquityRatio 깡통전세 기준 비율(%) — {@code risk_criteria.negative_equity_ratio}
     * @throws IllegalArgumentException 시세가 0 이하
     */
    public static NegativeEquityResult calculate(List<MortgageEntry> entries, long deposit, long marketPrice,
            BigDecimal negativeEquityRatio) {
        if (marketPrice <= 0) {
            throw new IllegalArgumentException("시세가 없어 깡통전세를 판정할 수 없다: " + marketPrice);
        }

        long seniorDebtTotal = 0;
        for (MortgageEntry entry : entries) {
            if (!entry.active()) {
                continue;
            }
            long priorTenantDeposit = entry.priorTenantDeposit() == null ? 0 : entry.priorTenantDeposit();
            seniorDebtTotal = Math.addExact(seniorDebtTotal, Math.addExact(entry.maxBondAmount(), priorTenantDeposit));
        }

        BigDecimal riskAmount = BigDecimal.valueOf(Math.addExact(seniorDebtTotal, deposit));
        BigDecimal market = BigDecimal.valueOf(marketPrice);

        boolean negativeEquity = riskAmount.multiply(PERCENT).compareTo(market.multiply(negativeEquityRatio)) > 0;
        BigDecimal debtRatio = riskAmount.multiply(PERCENT).divide(market, RATIO_SCALE, RoundingMode.HALF_UP);

        return new NegativeEquityResult(seniorDebtTotal, debtRatio, negativeEquity);
    }

    private NegativeEquityCalculator() {
    }
}
