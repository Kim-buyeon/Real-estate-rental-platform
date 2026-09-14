package com.duri.rentalplatform.domain.loan.calculator;

import com.duri.rentalplatform.domain.loan.enums.AppliedRegulation;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitCriteria;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitInput;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitResult;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 전세자금대출 한도를 계산한다(LOAN-01) — 비즈니스 로직 정의서 6장.
 *
 * <ul>
 *   <li>보증금 기준 한도 = 보증금 × 비율 ÷ 100, 원 단위 버림
 *   <li>보증기관 상한 = 주택 보유 여부로 선택
 *   <li>DSR 한도(주택 보유자만) = 연 이자 여력 ÷ (금리 ÷ 100), 원 단위 버림. 여력 = 연소득 × DSR ÷ 100 − 기존 연 상환액, 음수면 0
 *   <li>스트레스 DSR 한도(주택 보유자만, 참고) = 여력 ÷ ((금리 + 가산율) ÷ 100), 원 단위 버림. 최종 한도에 넣지 않는다
 *   <li>최종 한도 = min(보증금 기준, 보증기관 상한, DSR(해당 시), 상품 한도). 같으면 {@link AppliedRegulation} 선언 순서의 앞
 *   <li>DTI 참고 = (기존 연 상환액 + 최종 한도 × 금리 ÷ 100) ÷ 연소득 × 100, 소수 첫째 자리 HALF_UP. 소득 0 이면 null
 * </ul>
 *
 * <p>나눗셈은 백분율 환산을 분자로 먼저 곱해 한 번만 하고, 그 자리에서 버림 · 반올림한다 — 중간 반올림이 원 단위 결과를
 * 흔들지 않게 한다. 기준값은 모두 인자로 받는다.
 */
public final class JeonseLoanLimitCalculator {

    /** 백분율 환산 계수. 임계값이 아니다. */
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);
    private static final int DTI_SCALE = 1;

    private JeonseLoanLimitCalculator() {
    }

    /**
     * @throws IllegalArgumentException 금리가 0 이하이거나, 주택 보유자인데 연소득이 0 이하 — 후자는 호출부가 먼저
     *                                  {@code PROFILE_INCOMPLETE} 로 거른다
     */
    public static LoanLimitResult calculate(LoanLimitInput input, LoanLimitCriteria criteria) {
        if (criteria.interestRate().signum() <= 0) {
            throw new IllegalArgumentException("금리가 0 이하라 한도를 역산할 수 없다: " + criteria.interestRate());
        }
        if (input.hasHouse() && input.annualIncome() <= 0) {
            throw new IllegalArgumentException("주택 보유자는 연소득이 있어야 DSR 한도를 계산한다");
        }

        long depositLimit = BigDecimal.valueOf(input.deposit())
                .multiply(criteria.depositRatioLimit())
                .divide(PERCENT, 0, RoundingMode.DOWN)
                .longValueExact();
        long guaranteeCapLimit = input.hasHouse() ? criteria.guaranteeCapOneHouse() : criteria.guaranteeCapNoHouse();

        Long dsrLimit = null;
        Long stressDsrLimit = null;
        if (input.hasHouse()) {
            BigDecimal capacityTimesPercent = interestCapacity(input, criteria).multiply(PERCENT);
            dsrLimit = capacityTimesPercent.divide(criteria.interestRate(), 0, RoundingMode.DOWN).longValueExact();
            stressDsrLimit = capacityTimesPercent
                    .divide(criteria.interestRate().add(criteria.stressDsrRate()), 0, RoundingMode.DOWN)
                    .longValueExact();
        }

        // 선언 순서대로 보고 더 작을 때만 바꾼다 — 동률이면 앞 항목이 남는다.
        long finalLimit = depositLimit;
        AppliedRegulation applied = AppliedRegulation.DEPOSIT_RATIO;
        if (guaranteeCapLimit < finalLimit) {
            finalLimit = guaranteeCapLimit;
            applied = AppliedRegulation.GUARANTEE_CAP;
        }
        if (dsrLimit != null && dsrLimit < finalLimit) {
            finalLimit = dsrLimit;
            applied = AppliedRegulation.DSR;
        }
        if (criteria.productMaxLimit() < finalLimit) {
            finalLimit = criteria.productMaxLimit();
            applied = AppliedRegulation.PRODUCT_LIMIT;
        }

        return new LoanLimitResult(depositLimit, guaranteeCapLimit, dsrLimit, stressDsrLimit, finalLimit, applied,
                dtiReference(input, criteria, finalLimit));
    }

    /** 연 이자 여력(원) = 연소득 × DSR ÷ 100 − 기존 연 상환액. 음수면 0. 나눗셈 없이 정확히 둔다. */
    private static BigDecimal interestCapacity(LoanLimitInput input, LoanLimitCriteria criteria) {
        BigDecimal capacity = BigDecimal.valueOf(input.annualIncome())
                .multiply(criteria.dsrLimit())
                .divide(PERCENT)
                .subtract(BigDecimal.valueOf(input.existingLoanAnnualPayment()));
        return capacity.signum() < 0 ? BigDecimal.ZERO : capacity;
    }

    /** (기존 연 상환액 × 100 + 최종 한도 × 금리) ÷ 연소득 — 한 번의 나눗셈으로 % 를 낸다. */
    private static BigDecimal dtiReference(LoanLimitInput input, LoanLimitCriteria criteria, long finalLimit) {
        if (input.annualIncome() <= 0) {
            return null;
        }
        return BigDecimal.valueOf(input.existingLoanAnnualPayment()).multiply(PERCENT)
                .add(BigDecimal.valueOf(finalLimit).multiply(criteria.interestRate()))
                .divide(BigDecimal.valueOf(input.annualIncome()), DTI_SCALE, RoundingMode.HALF_UP);
    }
}
