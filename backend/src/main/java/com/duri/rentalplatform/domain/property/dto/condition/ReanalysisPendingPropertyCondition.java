package com.duri.rentalplatform.domain.property.dto.condition;

/**
 * 판정 입력(시세 · 등기 · 대장)이 바뀌어 재분석을 기다리는 매물 식별자의 한 페이지 — 매물 갱신 배치(RISK-08)의 판정 단계가 재분석 대상을 나눠 읽는다.
 *
 * @param lastPropertyId 이전 페이지 마지막 매물 식별자. 첫 페이지는 null
 * @param limit          페이지 크기
 */
public record ReanalysisPendingPropertyCondition(
        Long lastPropertyId,
        int limit
) {
}
