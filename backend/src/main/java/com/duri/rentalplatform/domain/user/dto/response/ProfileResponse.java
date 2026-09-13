package com.duri.rentalplatform.domain.user.dto.response;

import com.duri.rentalplatform.domain.user.enums.Role;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /api/me/profile} · {@code PUT /api/me/profile} 계정 · 자격 정보 응답. API 명세서(회원 · 인증) 1.1.
 */
public record ProfileResponse(

        Account account,

        Profile profile,

        List<String> missingFields
) {

    /** 계정 정보. 매퍼가 직접 채운다. */
    public record Account(
            String name,
            String phone,
            String email,
            Role role,
            OffsetDateTime createdAt
    ) {
    }

    /** 자격 정보. 매퍼가 직접 채운다. */
    public record Profile(
            Long annualIncome,
            Integer creditScore,
            Long existingLoan,
            Long existingLoanAnnualPayment,
            Boolean hasHouse,
            Long ownFund
    ) {
    }
}
