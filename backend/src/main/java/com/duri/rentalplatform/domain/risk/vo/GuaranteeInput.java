package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.HouseType;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import java.util.List;

/**
 * 보증보험 가입 판정(RISK-05)의 매물 쪽 입력. 앞선 판정의 결과를 모은다.
 *
 * @param marketPrice       시세(원). 0 이하이면 판정할 수 없다
 * @param deposit           보증금(원)
 * @param seniorDebtTotal   선순위채권 합계(원) — RISK-02 {@code seniorDebtTotal}
 * @param houseType         보증료율 표의 주택유형
 * @param violationBuilding 위반건축물인가 — RISK-04 {@code violationBuilding}
 * @param rightViolations   권리 침해 항목 — RISK-03 {@code rightViolations}
 * @param ownerNameMatched  명의 일치 — RISK-04
 * @param addressMatched    주소 일치 — RISK-04
 */
public record GuaranteeInput(
        long marketPrice,
        long deposit,
        long seniorDebtTotal,
        HouseType houseType,
        boolean violationBuilding,
        List<OwnershipRightType> rightViolations,
        boolean ownerNameMatched,
        boolean addressMatched
) {

    public GuaranteeInput {
        rightViolations = List.copyOf(rightViolations);
    }
}
