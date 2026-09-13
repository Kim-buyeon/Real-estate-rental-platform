package com.duri.rentalplatform.domain.admin.dto.response;

import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** {@code GET · PUT /api/admin/criteria/guarantee} 응답 — API 명세서(관리자) 1.1. */
public record GuaranteeCriteriaResponse(List<Provider> providers) {

    /**
     * 기관 한 곳. 매퍼가 직접 채운다. 드라이버는 시각을 UTC 오프셋으로 돌려주므로 서울 오프셋으로 바꿔 둔다(공통 규약 1.1).
     */
    public record Provider(
            GuaranteeProvider provider,
            BigDecimal collateralRatio,
            Long maxDeposit,
            BigDecimal seniorDebtRatioLimit,
            Boolean requiresLoanLink,
            OffsetDateTime updatedAt
    ) {

        private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

        public Provider {
            if (updatedAt != null) {
                updatedAt = updatedAt.atZoneSameInstant(SEOUL).toOffsetDateTime();
            }
        }
    }
}
