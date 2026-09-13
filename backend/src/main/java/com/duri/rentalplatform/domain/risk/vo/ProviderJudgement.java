package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import java.util.List;

/**
 * 한 보증기관의 가입 판정 결과 — 위험도 응답 {@code providers[]}.
 *
 * @param provider         보증기관
 * @param eligible         가입 가능 — 위배 조건이 없다
 * @param failedConditions 위배된 조건 전부. 선언 순서
 * @param loanLinkRequired 대출 연계가 필요한 기관인가(HF). 가입 불가 사유가 아니다
 * @param guaranteeLimit   보증한도(원) — 시세 × 담보인정비율 ÷ 100 − 선순위채권, 원 단위 버림, 0 미만이면 0
 * @param estimatedPremium 연 예상 보증료(원, 반올림). 가입 불가이거나 맞는 요율 행이 없으면 null
 * @param productName      상품명. 가입 불가면 null
 */
public record ProviderJudgement(
        GuaranteeProvider provider,
        boolean eligible,
        List<GuaranteeFailedCondition> failedConditions,
        boolean loanLinkRequired,
        long guaranteeLimit,
        Long estimatedPremium,
        String productName
) {

    public ProviderJudgement {
        failedConditions = List.copyOf(failedConditions);
    }
}
