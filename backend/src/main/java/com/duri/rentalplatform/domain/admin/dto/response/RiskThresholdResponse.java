package com.duri.rentalplatform.domain.admin.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** {@code GET · PUT /api/admin/criteria/risk-thresholds} 응답 — API 명세서(관리자) 1.1. 매퍼가 직접 채운다. */
public record RiskThresholdResponse(
        BigDecimal negativeEquityRatio,
        BigDecimal cautionLeaseRatio,
        OffsetDateTime updatedAt
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public RiskThresholdResponse {
        if (updatedAt != null) {
            updatedAt = updatedAt.atZoneSameInstant(SEOUL).toOffsetDateTime();
        }
    }
}
