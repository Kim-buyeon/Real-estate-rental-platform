package com.duri.rentalplatform.domain.property.dto.condition;

/**
 * 관심 매물로 등록된 서로 다른 매물 식별자의 한 페이지 — 등기 재조회 배치(RISK-08)가 대상을 나눠 읽는다.
 *
 * @param lastPropertyId 이전 페이지 마지막 매물 식별자. 첫 페이지는 null
 * @param limit 페이지 크기
 */
public record WishlistedPropertyCondition(
        Long lastPropertyId,
        int limit
) {
}
