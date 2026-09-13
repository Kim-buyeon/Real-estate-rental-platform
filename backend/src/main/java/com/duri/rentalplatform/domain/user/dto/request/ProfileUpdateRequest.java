package com.duri.rentalplatform.domain.user.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * {@code PUT /api/me/profile} 계정 · 자격 정보 수정 요청. API 명세서(회원 · 인증) 1.1.
 *
 * <p>수정 가능한 필드만 둔다. 명세는 수정 불가 필드(email · role · createdAt)가 요청에 섞이면 무시한다고 정했고,
 * 선언되지 않은 필드는 역직렬화가 버린다.
 */
public record ProfileUpdateRequest(

        @NotNull
        @Valid
        Account account,

        @NotNull
        @Valid
        Profile profile
) {

    public record Account(

            @NotBlank
            @Size(max = 50)
            String name,

            // 필수로 두지 않는 이유: users.phone 이 NULL 을 허용하고 명세가 필수 여부를 정하지 않았다. 가입 요청과 같다.
            @Size(max = 20)
            String phone
    ) {
    }

    /**
     * 자격 정보. 명세는 미입력 항목을 0 또는 null 로 보낸다고 정했고 컬럼은 NOT NULL 이라, null 은 compact 생성자가
     * 미입력을 뜻하는 0 · false 로 채운다. 가입 시 채우는 값과 같다.
     */
    public record Profile(

            @PositiveOrZero
            Long annualIncome,

            // 상한 1000: 국내 개인 신용점수(NICE · KCB)가 1000점 만점이다. 0 은 미입력이다.
            @Min(0)
            @Max(1000)
            Integer creditScore,

            @PositiveOrZero
            Long existingLoan,

            @PositiveOrZero
            Long existingLoanAnnualPayment,

            Boolean hasHouse,

            @PositiveOrZero
            Long ownFund
    ) {

        public Profile {
            annualIncome = annualIncome == null ? 0L : annualIncome;
            creditScore = creditScore == null ? 0 : creditScore;
            existingLoan = existingLoan == null ? 0L : existingLoan;
            existingLoanAnnualPayment = existingLoanAnnualPayment == null ? 0L : existingLoanAnnualPayment;
            hasHouse = hasHouse != null && hasHouse;
            ownFund = ownFund == null ? 0L : ownFund;
        }
    }
}
