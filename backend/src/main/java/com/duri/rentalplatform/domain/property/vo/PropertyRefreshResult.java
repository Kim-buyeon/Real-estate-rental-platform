package com.duri.rentalplatform.domain.property.vo;

import java.util.List;

/**
 * 갱신 적재(RISK-08) 한 번이 판정 단계에 넘기는 매물 식별자. 건수 · 실패 사유는 {@link PropertyLoadReport} 가 갖는다.
 *
 * @param newPropertyIds          이번 적재가 새로 저장한 매물. 아직 판정이 없다
 * @param priceChangedPropertyIds 자연키가 같은 기존 매물 중 시세 금액이 바뀌어 갱신한 매물. 재분석 대상이다 — 데이터 적재
 *                                설계서 1.5 「시세가 실제로 변경된 매물만 재분석 대상으로 표시」. 기준일만 바뀐 매물은 저장값은
 *                                갱신하되 여기에 넣지 않는다 — 판정 입력이 그대로라 결과도 같다
 */
public record PropertyRefreshResult(
        List<Long> newPropertyIds,
        List<Long> priceChangedPropertyIds
) {
    public PropertyRefreshResult {
        newPropertyIds = List.copyOf(newPropertyIds);
        priceChangedPropertyIds = List.copyOf(priceChangedPropertyIds);
    }
}
