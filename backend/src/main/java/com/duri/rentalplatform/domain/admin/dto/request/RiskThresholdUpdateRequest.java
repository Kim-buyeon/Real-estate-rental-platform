package com.duri.rentalplatform.domain.admin.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** {@code PUT /api/admin/criteria/risk-thresholds} — API 명세서(관리자) 1.1. 세 선 단조 검증은 서비스가 한다. */
public record RiskThresholdUpdateRequest(
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal negativeEquityRatio,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal cautionLeaseRatio,
        @NotBlank @Size(max = 200) String changeReason
) {
}
