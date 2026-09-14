package com.duri.rentalplatform.domain.admin.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code PUT /api/admin/criteria/loan-regulations} — 적용 대상(주택 · 지역 유형, 시행일)은 두고 수치 여섯만 바꾼다.
 * API 명세서(관리자) 1.1.
 */
public record LoanRegulationUpdateRequest(
        @NotEmpty List<@Valid @NotNull Regulation> regulations,
        @NotBlank @Size(max = 200) String changeReason
) {

    /** 규제 한 행. 비율은 0 이상 100 미만, 소수 둘째 자리까지(NUMERIC(5, 2)). 상한은 0 이상 원. */
    public record Regulation(
            @NotNull Long regulationId,
            @NotNull @DecimalMin("0") @Digits(integer = 2, fraction = 2) BigDecimal depositRatioLimit,
            @NotNull @Min(0) Long guaranteeCapNoHouse,
            @NotNull @Min(0) Long guaranteeCapOneHouse,
            @NotNull @DecimalMin("0") @Digits(integer = 2, fraction = 2) BigDecimal dsrLimit,
            @NotNull @DecimalMin("0") @Digits(integer = 2, fraction = 2) BigDecimal stressDsrRate,
            @NotNull @DecimalMin("0") @Digits(integer = 2, fraction = 2) BigDecimal dtiLimit
    ) {
    }
}
