package com.duri.rentalplatform.domain.loan.vo;

import com.duri.rentalplatform.domain.property.enums.PropertyType;
import java.util.Map;
import java.util.Optional;

/**
 * 대출 한도 기준표 한 벌 — 대출 기준 슬롯 캐시({@code LoanCriteriaCache})가 들고 있는 불변 묶음.
 *
 * @param limitCriteriaByType 매물 유형별 한도 기준값 — 최신 대출 규제 한 행 + 그 유형의 대표 대출 상품. 규제나 대표 상품이 없는
 *                            유형은 빠진다(시드 결함 — 한도 조회가 500 으로 낸다)
 */
public record LoanCriteria(
        Map<PropertyType, LoanLimitCriteria> limitCriteriaByType
) {

    public LoanCriteria {
        limitCriteriaByType = Map.copyOf(limitCriteriaByType);
    }

    /** 매물 유형의 한도 기준값. 규제나 대표 상품이 없으면 빈 값. */
    public Optional<LoanLimitCriteria> findLoanLimitCriteria(PropertyType propertyType) {
        return Optional.ofNullable(limitCriteriaByType.get(propertyType));
    }
}
