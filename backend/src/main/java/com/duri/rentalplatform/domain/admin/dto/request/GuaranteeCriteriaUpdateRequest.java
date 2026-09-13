package com.duri.rentalplatform.domain.admin.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * {@code PUT /api/admin/criteria/guarantee/{provider}} — API 명세서(관리자) 1.1.
 *
 * <p>{@code seniorDebtRatioLimit} 이 null 이면 기존 값을 유지한다. {@code requiresLoanLink} 는 HF 만 저장한다.
 */
public record GuaranteeCriteriaUpdateRequest(
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal collateralRatio,
        @NotNull @PositiveOrZero Long maxDeposit,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal seniorDebtRatioLimit,
        @NotNull Boolean requiresLoanLink,
        @NotBlank @Size(max = 200) String changeReason
) {
}
