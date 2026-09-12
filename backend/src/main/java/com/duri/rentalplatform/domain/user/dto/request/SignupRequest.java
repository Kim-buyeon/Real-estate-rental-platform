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

        // 필수로 두지 않는 이유: users.phone이 NULL을 허용하고, API 명세는 이 필드의 필수 여부를 정하지 않았다.
        // 명세가 정하지 않은 것은 스키마를 따른다. 여기서 @NotBlank를 더하면 코드에만 있는 규칙이 된다.
        @Size(max = 20)
        String phone
) {
}
