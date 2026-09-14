package com.duri.rentalplatform.domain.loan.dto.response;

import com.duri.rentalplatform.domain.loan.enums.AppliedRegulation;
import com.duri.rentalplatform.domain.loan.vo.LoanLimitResult;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET /api/loans/limit} 응답 — API 명세서(대출) 1.1. {@code dsrLimit} · {@code stressDsrLimit} ·
 * {@code dtiReference} 는 해당하지 않으면 null 로 내보낸다(필드를 생략하지 않는다).
 *
 * <p>{@code missingFields} 는 성공 응답에서 늘 빈 배열이다. 자격 정보가 부족하면 422 오류 봉투의 {@code field} 로 알린다.
 */
public record LoanLimitResponse(
        long depositLimit,
        long guaranteeCapLimit,
        Long dsrLimit,
        Long stressDsrLimit,
        long finalLimit,
        AppliedRegulation appliedRegulation,
        BigDecimal dtiReference,
        List<String> missingFields
) {

    public static LoanLimitResponse from(LoanLimitResult result) {
        return new LoanLimitResponse(result.depositLimit(), result.guaranteeCapLimit(), result.dsrLimit(),
                result.stressDsrLimit(), result.finalLimit(), result.appliedRegulation(), result.dtiReference(),
                List.of());
    }
}
