package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.PersonalCondition;
import java.util.List;

/**
 * 보증보험 3사 가입 판정(RISK-05) 결과.
 *
 * @param insuranceEligible  하나 이상의 기관이 가입 가능
 * @param providers          기관별 결과. 기준을 넘긴 순서
 * @param personalConditions 시스템이 판정하지 않는 개인 자격 확인 사항
 */
public record GuaranteeJudgementResult(
        boolean insuranceEligible,
        List<ProviderJudgement> providers,
        List<PersonalCondition> personalConditions
) {

    public GuaranteeJudgementResult {
        providers = List.copyOf(providers);
        personalConditions = List.copyOf(personalConditions);
    }
}
