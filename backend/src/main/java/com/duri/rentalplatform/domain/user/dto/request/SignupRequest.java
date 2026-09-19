package com.duri.rentalplatform.domain.user.dto.request;

import com.duri.rentalplatform.common.validation.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/signup} 이메일 회원 가입 요청.
 */
public record SignupRequest(

        @NotBlank
        @Email
        @Size(max = 100)
        String email,

        @NotBlank
        @PasswordPolicy
        String password,

        @NotBlank
        @Size(max = 50)
        String name,

        // 선택 항목이다 — API 명세(회원) 1.2. users.phone도 NULL을 허용한다.
        @Size(max = 20)
        String phone
) {
}
