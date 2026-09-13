package com.duri.rentalplatform.domain.admin.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** {@code PUT /api/admin/criteria/premium-rates} — 요율 값만 바꾼다. API 명세서(관리자) 1.1. */
public record PremiumRateUpdateRequest(
        @NotEmpty List<@Valid @NotNull Rate> rates,
        @NotBlank @Size(max = 200) String changeReason
) {

    /** 요율 한 구간. 0 이상 100 미만, 소수 셋째 자리까지(NUMERIC(5, 3)). */
    public record Rate(
            @NotNull Long premiumRateId,
            @NotNull @DecimalMin("0") @Digits(integer = 2, fraction = 3) BigDecimal premiumRate
    ) {
    }
}
