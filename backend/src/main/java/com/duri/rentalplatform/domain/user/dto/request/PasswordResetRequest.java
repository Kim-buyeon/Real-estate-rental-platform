package com.duri.rentalplatform.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/password-reset} 비밀번호 재설정 메일 요청 — API 명세(회원) 1.3.
 *
 * <p>형식 검증은 가입과 같다. 가입 여부와 무관한 입력 검증이라 400 이어도 가입 여부가 드러나지 않는다.
 */
public record PasswordResetRequest(

        @NotBlank
        @Email
        @Size(max = 100)
        String email
) {
}
