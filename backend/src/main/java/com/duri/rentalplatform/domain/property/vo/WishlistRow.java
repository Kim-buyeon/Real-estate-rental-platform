package com.duri.rentalplatform.domain.property.vo;

import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import java.time.OffsetDateTime;

/**
 * 관심 매물 목록의 매퍼 행. 응답(API 명세서(매물) 1.9)에 없는 {@code wishId} 를 커서 생성용으로 함께 받는다.
 *
 * @param riskGrade 최신 분석의 등급. 분석 전이면 null
 * @param previousGrade 최신 분석의 직전 등급. 분석 전이거나 첫 분석이면 null
 * @param addedAt 드라이버 오프셋 그대로다. 서울 오프셋 맞춤은 응답이 한다
 */
public record WishlistRow(
        Long wishId,
        Long propertyId,
        String district,
        Long deposit,
        RiskGrade riskGrade,
        RiskGrade previousGrade,
        OffsetDateTime addedAt
) {
}
