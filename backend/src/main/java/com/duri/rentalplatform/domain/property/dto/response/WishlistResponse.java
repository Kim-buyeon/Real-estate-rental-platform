package com.duri.rentalplatform.domain.property.dto.response;

import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.vo.WishlistRow;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 관심 매물 목록 한 건 — API 명세서(매물) 1.9.
 *
 * @param riskGrade 최신 분석의 등급. 분석 전이면 null
 * @param previousGrade 직전 등급. 분석 전이거나 첫 분석이면 null
 */
public record WishlistResponse(
        Long propertyId,
        String district,
        Long deposit,
        RiskGrade riskGrade,
        RiskGrade previousGrade,
        OffsetDateTime addedAt
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 등록 시각을 서울 오프셋으로 맞춘다 — 공통 규약 1.1. */
    public static WishlistResponse of(WishlistRow row) {
        return new WishlistResponse(
                row.propertyId(),
                row.district(),
                row.deposit(),
                row.riskGrade(),
                row.previousGrade(),
                row.addedAt().atZoneSameInstant(SEOUL).toOffsetDateTime());
    }
}
