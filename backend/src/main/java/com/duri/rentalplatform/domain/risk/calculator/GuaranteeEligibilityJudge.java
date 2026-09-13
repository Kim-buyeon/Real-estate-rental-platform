package com.duri.rentalplatform.domain.risk.calculator;

import com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition;
import com.duri.rentalplatform.domain.risk.enums.HouseType;
import com.duri.rentalplatform.domain.risk.enums.PersonalCondition;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeCriteriaSnapshot;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeInput;
import com.duri.rentalplatform.domain.risk.vo.GuaranteeJudgementResult;
import com.duri.rentalplatform.domain.risk.vo.PremiumRateBand;
import com.duri.rentalplatform.domain.risk.vo.ProviderJudgement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 보증보험 3사 가입을 판정한다(RISK-05) — business-logic.md 2장.
 *
 * <p>기관마다 집 단위 조건을 모두 검사하고 위배를 <b>전부</b> 모은다. 하나라도 있으면 그 기관은 가입 불가다.
 * 비율 비교는 반올림 없이 {@code 금액 × 100 > 시세 × 비율} 로 한다 — 반올림한 비율로 비교하면 경계 +1원이 뒤집힌다.
 *
 * <ul>
 *   <li>선순위채권 한도가 null 인 기관은 그 조건을 검사하지 않는다.</li>
 *   <li>보증금 한도는 기준의 아파트 무제한 여부가 참이고 주택유형이 아파트면 검사하지 않는다(SGI).</li>
 *   <li>대출 연계(HF)는 가입 불가 사유가 아니라 결과에 표기한다.</li>
 *   <li>보증한도는 가입 여부와 무관하게 낸다. 원 단위 버림, 0 미만이면 0.</li>
 *   <li>예상 보증료(연)는 가입 가능할 때만, 맞는 요율 행이 없으면 null. 원 단위 반올림.</li>
 * </ul>
 *
 * <p>기준값은 전부 {@link GuaranteeCriteriaSnapshot} 으로 받는다. 이 클래스는 임계값을 갖지 않는다.
 */
public final class GuaranteeEligibilityJudge {

    /** 백분율 환산 계수. 임계값이 아니다. */
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    /** 개인 자격 확인 사항은 매물과 무관하게 늘 같다. */
    private static final List<PersonalCondition> PERSONAL_CONDITIONS = List.of(PersonalCondition.values());

    /**
     * @param input    매물 쪽 입력
     * @param criteria 기관별 기준. 결과는 이 순서로 나간다
     * @throws IllegalArgumentException 시세가 0 이하
     */
    public static GuaranteeJudgementResult judge(GuaranteeInput input, List<GuaranteeCriteriaSnapshot> criteria) {
        if (input.marketPrice() <= 0) {
            throw new IllegalArgumentException("시세가 없어 보증보험 가입을 판정할 수 없다: " + input.marketPrice());
        }
        List<ProviderJudgement> providers = criteria.stream()
                .map(snapshot -> judgeProvider(input, snapshot))
                .toList();
        boolean insuranceEligible = providers.stream().anyMatch(ProviderJudgement::eligible);
        return new GuaranteeJudgementResult(insuranceEligible, providers, PERSONAL_CONDITIONS);
    }

    private static ProviderJudgement judgeProvider(GuaranteeInput input, GuaranteeCriteriaSnapshot criteria) {
        BigDecimal market = BigDecimal.valueOf(input.marketPrice());
        BigDecimal seniorDebt = BigDecimal.valueOf(input.seniorDebtTotal());
        BigDecimal riskAmountPercent = seniorDebt.add(BigDecimal.valueOf(input.deposit())).multiply(PERCENT);

        List<GuaranteeFailedCondition> failed = new ArrayList<>();
        if (riskAmountPercent.compareTo(market.multiply(criteria.collateralRatio())) > 0) {
            failed.add(GuaranteeFailedCondition.DEBT_RATIO_EXCEEDED);
        }
        if (criteria.seniorDebtRatioLimit() != null
                && seniorDebt.multiply(PERCENT).compareTo(market.multiply(criteria.seniorDebtRatioLimit())) > 0) {
            failed.add(GuaranteeFailedCondition.SENIOR_DEBT_RATIO_EXCEEDED);
        }
        boolean depositUnlimited = criteria.apartmentUnlimited() && input.houseType() == HouseType.APARTMENT;
        if (!depositUnlimited && input.deposit() > criteria.maxDeposit()) {
            failed.add(GuaranteeFailedCondition.DEPOSIT_LIMIT_EXCEEDED);
        }
        if (input.violationBuilding() && criteria.violationDisqualify()) {
            failed.add(GuaranteeFailedCondition.VIOLATION_BUILDING);
        }
        if (!input.rightViolations().isEmpty() && criteria.rightViolationDisqualify()) {
            failed.add(GuaranteeFailedCondition.RIGHT_VIOLATION);
        }
        if (!input.ownerNameMatched()) {
            failed.add(GuaranteeFailedCondition.OWNER_MISMATCH);
        }
        if (!input.addressMatched()) {
            failed.add(GuaranteeFailedCondition.ADDRESS_MISMATCH);
        }

        boolean eligible = failed.isEmpty();
        return new ProviderJudgement(
                criteria.provider(),
                eligible,
                failed,
                criteria.loanLinkRequired(),
                guaranteeLimit(market, seniorDebt, criteria.collateralRatio()),
                eligible ? estimatedPremium(input, market, riskAmountPercent, criteria.premiumRates()) : null,
                eligible ? criteria.productName() : null);
    }

    private static long guaranteeLimit(BigDecimal market, BigDecimal seniorDebt, BigDecimal collateralRatio) {
        BigDecimal limit = market.multiply(collateralRatio).divide(PERCENT, 0, RoundingMode.DOWN).subtract(seniorDebt);
        return limit.signum() < 0 ? 0L : limit.longValueExact();
    }

    private static Long estimatedPremium(GuaranteeInput input, BigDecimal market, BigDecimal riskAmountPercent,
            List<PremiumRateBand> bands) {
        return bands.stream()
                .filter(band -> band.houseType() == input.houseType())
                .filter(band -> inDepositRange(input.deposit(), band))
                .filter(band -> inDebtRatioRange(market, riskAmountPercent, band))
                .findFirst()
                .map(band -> BigDecimal.valueOf(input.deposit()).multiply(band.premiumRate())
                        .divide(PERCENT, 0, RoundingMode.HALF_UP).longValueExact())
                .orElse(null);
    }

    private static boolean inDepositRange(long deposit, PremiumRateBand band) {
        return deposit >= band.depositMin() && (band.depositMax() == null || deposit <= band.depositMax());
    }

    /** {@code min < 전세가율 ≤ max}, min 이 0 이면 0 포함. 반올림 없이 {@code 위험금액 × 100} 과 {@code 시세 × 경계} 로 비교한다. */
    private static boolean inDebtRatioRange(BigDecimal market, BigDecimal riskAmountPercent, PremiumRateBand band) {
        int toMin = riskAmountPercent.compareTo(market.multiply(band.debtRatioMin()));
        boolean aboveMin = band.debtRatioMin().signum() == 0 ? toMin >= 0 : toMin > 0;
        return aboveMin && riskAmountPercent.compareTo(market.multiply(band.debtRatioMax())) <= 0;
    }

    private GuaranteeEligibilityJudge() {
    }
}
