package com.duri.rentalplatform.domain.admin.dto.response;

import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.HouseType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** {@code GET · PUT /api/admin/criteria/premium-rates} 응답 — API 명세서(관리자) 1.1. 커서 없는 전체 목록. */
public record PremiumRatesResponse(List<Item> items) {

    /** 요율 한 구간. 매퍼가 직접 채운다. 시각은 서울 오프셋으로 바꾼다(공통 규약 1.1). */
    public record Item(
            Long premiumRateId,
            GuaranteeProvider provider,
            HouseType houseType,
            Long depositMin,
            Long depositMax,
            BigDecimal debtRatioMin,
            BigDecimal debtRatioMax,
            BigDecimal premiumRate,
            OffsetDateTime updatedAt
    ) {

        private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

        public Item {
            if (updatedAt != null) {
                updatedAt = updatedAt.atZoneSameInstant(SEOUL).toOffsetDateTime();
            }
        }
    }
}
