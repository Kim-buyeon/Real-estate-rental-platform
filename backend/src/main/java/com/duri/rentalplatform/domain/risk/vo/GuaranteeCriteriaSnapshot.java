package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import java.math.BigDecimal;
import java.util.List;

/**
 * 한 보증기관의 판정 기준. 호출부가 기준 테이블에서 읽어 넘긴다 — 판정기는 기준값을 갖지 않는다.
 *
 * @param provider                 보증기관
 * @param collateralRatio          담보인정비율(%) — {@code guarantee_criteria.collateral_ratio}
 * @param seniorDebtRatioLimit     선순위채권 한도(%) — {@code guarantee_criteria.senior_debt_ratio_limit}. null 이면 검사하지 않는다
 * @param maxDeposit               최대 보증 가능 보증금(원) — {@code guarantee_criteria.max_deposit}
 * @param apartmentUnlimited       아파트 보증금 한도 없음 — {@code sgi_criteria.apartment_unlimited_yn}. 그 행이 없는 기관은 false
 * @param violationDisqualify      위반건축물이면 가입 불가 — {@code guarantee_criteria.violation_disqualify_yn}
 * @param rightViolationDisqualify 권리 침해가 있으면 가입 불가 — {@code guarantee_criteria.right_violation_disqualify_yn}
 * @param loanLinkRequired         전세자금보증부 대출 연계 필요 — {@code hf_criteria.loan_linked_required_yn}. 그 행이 없는 기관은 false
 * @param productName              상품명 — {@code insurance_product.product_name}
 * @param premiumRates             보증료율 표 — {@code guarantee_premium_rate}. 없으면 빈 목록
 */
public record GuaranteeCriteriaSnapshot(
        GuaranteeProvider provider,
        BigDecimal collateralRatio,
        BigDecimal seniorDebtRatioLimit,
        long maxDeposit,
        boolean apartmentUnlimited,
        boolean violationDisqualify,
        boolean rightViolationDisqualify,
        boolean loanLinkRequired,
        String productName,
        List<PremiumRateBand> premiumRates
) {

    public GuaranteeCriteriaSnapshot {
        premiumRates = List.copyOf(premiumRates);
    }
}
