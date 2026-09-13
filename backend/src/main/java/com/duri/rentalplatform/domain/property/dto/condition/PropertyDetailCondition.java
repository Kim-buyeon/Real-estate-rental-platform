package com.duri.rentalplatform.domain.property.dto.condition;

/**
 * 매물 상세 조회 조건.
 *
 * @param userId 인증 사용자. 비인증이면 null 이고 관심 등록 여부는 false 로 조회된다
 */
public record PropertyDetailCondition(Long propertyId, Long userId) {
}
