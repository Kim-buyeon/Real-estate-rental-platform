package com.duri.rentalplatform.domain.risk.vo;

/**
 * 매물 갱신 배치(RISK-08) 판정 단계의 대상 한 건.
 *
 * <p>갈래마다 부르는 판정이 다르다 — 첫 판정은 {@code RiskAnalysisCommandService.analyze}(등기 · 대장 수집), 시세 변경
 * 재분석은 {@code analyzeWithCollectedLedger}(대장 수집 없음, #328). 이전 등급 보존과 등급 변동 이벤트는 두 메서드가 공유하는
 * 판정 저장이 갖는다. 갈래는 집계에서 「첫 판정」과 「시세 변경 재분석」을 따로 세는 데에도 쓴다.
 *
 * @param propertyId   매물 식별자
 * @param priceChanged 시세 금액이 바뀌어 재분석하는, 최신 판정이 있는 매물인가. 거짓이면 최신 판정이 없어 처음 판정하는
 *                     매물(신규 매물 포함). 재분석 대기 표시가 있을 수 있다(#338)
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
