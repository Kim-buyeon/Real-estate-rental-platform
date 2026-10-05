package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.loan.vo.LoanLimitCriteria;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 판정 기준표 한 벌 — 기준표 슬롯 캐시({@code JudgementCriteriaCache})가 들고 있는 불변 묶음. 기준 엔티티를 그대로 두지 않고 판정 ·
 * 한도 계산이 읽는 값만 옮겨 둔다 — 엔티티는 읽은 영속성 컨텍스트 밖에서 지연 로딩이 닿지 않고, 여러 요청 스레드가 함께 읽는다.
 *
 * @param guaranteeCriteria   기관별 판정 기준. HUG → HF → SGI 순 — 판정기에 넘기는 순서가 응답 순서다
 * @param guaranteeIds        기관별 보증 기준 ID — 분석 행의 가입 가능 첫 기관({@code eligible_guarantee_id})
 * @param negativeEquityRatio 깡통전세 선(%) — {@code risk_criteria.negative_equity_ratio}
 * @param cautionLeaseRatio   SAFE/CAUTION 경계(%) — {@code risk_criteria.caution_lease_ratio}
 * @param loanLimitCriteria   매물 유형별 대출 한도 기준값 — 최신 대출 규제 한 행 + 그 유형의 대표 대출 상품. 규제나 대표 상품이
 *                            없는 유형은 빠진다(시드 결함 — 한도 조회가 500 으로 낸다)
 * @param fingerprint         위험도 판정 기준의 지문({@code CriteriaFingerprintCalculator}). 대출 기준값은 들어가지 않는다
 */
public record JudgementCriteria(
        List<GuaranteeCriteriaSnapshot> guaranteeCriteria,
        Map<GuaranteeProvider, Long> guaranteeIds,
        BigDecimal negativeEquityRatio,
        BigDecimal cautionLeaseRatio,
        Map<PropertyType, LoanLimitCriteria> loanLimitCriteria,
        String fingerprint
) {

    public JudgementCriteria {
        guaranteeCriteria = List.copyOf(guaranteeCriteria);
        guaranteeIds = Map.copyOf(guaranteeIds);
        loanLimitCriteria = Map.copyOf(loanLimitCriteria);
    }

    /** 기관의 보증 기준 ID. 기준 행이 없는 기관이면 null. */
    public Long guaranteeIdOf(GuaranteeProvider provider) {
        return guaranteeIds.get(provider);
    }

    /** 매물 유형의 대출 한도 기준값. 규제나 대표 상품이 없으면 빈 값. */
    public Optional<LoanLimitCriteria> findLoanLimitCriteria(PropertyType propertyType) {
        return Optional.ofNullable(loanLimitCriteria.get(propertyType));
    }
}
