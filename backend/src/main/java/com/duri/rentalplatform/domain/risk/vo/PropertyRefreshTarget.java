package com.duri.rentalplatform.domain.risk.vo;

/**
 * 매물 갱신 배치(RISK-08) 판정 단계의 대상 한 건.
 *
 * <p>두 갈래 모두 같은 판정({@code RiskAnalysisCommandService.analyze})을 거친다 — 이전 등급 보존과 등급 변동 이벤트는 그
 * 판정이 저장할 때 갖는다. 갈래를 나눠 두는 것은 집계에서 「첫 판정」과 「시세 변경 재분석」을 따로 세기 위해서다.
 *
 * @param propertyId   매물 식별자
 * @param priceChanged 시세 금액이 바뀌어 재분석하는 매물인가. 거짓이면 최신 판정이 없어 처음 판정하는 매물(신규 매물 포함)
 */
public record PropertyRefreshTarget(
        Long propertyId,
        boolean priceChanged
) {

    public static PropertyRefreshTarget priceChanged(Long propertyId) {
        return new PropertyRefreshTarget(propertyId, true);
    }

    public static PropertyRefreshTarget unanalyzed(Long propertyId) {
        return new PropertyRefreshTarget(propertyId, false);
    }
}
