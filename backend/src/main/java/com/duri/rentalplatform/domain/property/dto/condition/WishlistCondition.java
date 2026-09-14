package com.duri.rentalplatform.domain.property.dto.condition;

/**
 * 관심 매물 목록 조회 조건. 커서 문자열을 마지막 행의 관심 매물 식별자로 푼 값이다.
 *
 * @param userId 인증 사용자
 * @param lastWishId 이전 페이지 마지막 행의 {@code wish_id}. 첫 페이지는 null
 * @param limit 다음 페이지 판정을 위해 요청 크기 + 1
 */
public record WishlistCondition(
        Long userId,
        Long lastWishId,
        int limit
) {
}
