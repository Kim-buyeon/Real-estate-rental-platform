package com.duri.rentalplatform.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/login} 이메일 로그인 요청.
 */
public record LoginRequest(

        @NotBlank
        @Email
        @Size(max = 100)
        String email,

        // @PasswordPolicy 를 붙이지 않는 이유: 그 제약은 새 비밀번호를 받을 때 쓰는 것이다.
        // 로그인은 이미 저장된 비밀번호와 대조할 뿐이다. 정책이 나중에 강화되면 이전 정책으로
        // 가입한 사용자가 자기 비밀번호를 정확히 입력하고도 로그인 단계에서 400 으로 막힌다.
        // 그 사용자에게 필요한 것은 형식 오류가 아니라 비밀번호 변경 안내다.
        @NotBlank
        String password
) {
}
