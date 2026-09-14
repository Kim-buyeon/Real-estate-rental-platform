package com.duri.rentalplatform.domain.admin.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET · PUT /api/admin/criteria/loan-regulations} 응답 — API 명세서(관리자) 1.1. 커서 없는 전체 목록.
 * 테이블에 수정일시 컬럼이 없어 {@code updatedAt} 이 없다.
 */
public record LoanRegulationsResponse(List<Item> items) {

    /** 규제 한 행. 매퍼가 직접 채운다. */
    public record Item(
            Long regulationId,
            String houseType,
            String regionType,
            BigDecimal depositRatioLimit,
            Long guaranteeCapNoHouse,
            Long guaranteeCapOneHouse,
            BigDecimal dsrLimit,
            BigDecimal stressDsrRate,
            BigDecimal dtiLimit,
            LocalDate effectiveDate
    ) {
    }
}
