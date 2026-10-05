package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 위험도 판정 기준표 한 벌 — 기준표 슬롯 캐시({@code JudgementCriteriaCache})가 들고 있는 불변 묶음. 기준 엔티티를 그대로 두지 않고
 * 판정이 읽는 값만 옮겨 둔다 — 엔티티는 읽은 영속성 컨텍스트 밖에서 지연 로딩이 닿지 않고, 여러 요청 스레드가 함께 읽는다.
 *
 * @param guaranteeCriteria   기관별 판정 기준. HUG → HF → SGI 순 — 판정기에 넘기는 순서가 응답 순서다
 * @param guaranteeIds        기관별 보증 기준 ID — 분석 행의 가입 가능 첫 기관({@code eligible_guarantee_id})
 * @param negativeEquityRatio 깡통전세 선(%) — {@code risk_criteria.negative_equity_ratio}
 * @param cautionLeaseRatio   SAFE/CAUTION 경계(%) — {@code risk_criteria.caution_lease_ratio}
 * @param fingerprint         이 기준의 지문({@code CriteriaFingerprintCalculator})
 */
public record JudgementCriteria(
        List<GuaranteeCriteriaSnapshot> guaranteeCriteria,
        Map<GuaranteeProvider, Long> guaranteeIds,
        BigDecimal negativeEquityRatio,
        BigDecimal cautionLeaseRatio,
        String fingerprint
) {

    public JudgementCriteria {
        guaranteeCriteria = List.copyOf(guaranteeCriteria);
        guaranteeIds = Map.copyOf(guaranteeIds);
    }

    /** 기관의 보증 기준 ID. 기준 행이 없는 기관이면 null. */
    public Long guaranteeIdOf(GuaranteeProvider provider) {
        return guaranteeIds.get(provider);
    }
}
