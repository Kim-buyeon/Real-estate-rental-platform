package com.duri.rentalplatform.domain.property.dto.condition;

/**
 * 최신 판정이 없는 매물 식별자의 한 페이지 — 매물 갱신 배치(RISK-08)의 판정 단계가 대상을 나눠 읽는다.
 *
 * @param lastPropertyId 이전 페이지 마지막 매물 식별자. 첫 페이지는 null
 * @param limit          페이지 크기
 */
public record UnanalyzedPropertyCondition(
        Long lastPropertyId,
        int limit
) {
}
