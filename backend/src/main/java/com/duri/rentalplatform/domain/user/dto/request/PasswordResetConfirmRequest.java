package com.duri.rentalplatform.domain.user.dto.request;

import com.duri.rentalplatform.common.validation.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/auth/password-reset/confirm} 재설정 토큰으로 새 비밀번호 설정 — API 명세(회원) 1.3.
 *
 * <p>검증이 컨트롤러에서 끝나므로 규칙을 어긴 요청은 서비스에 닿지 않고, 토큰도 소비되지 않는다.
 */
public record PasswordResetConfirmRequest(

        // 형식 제약을 두지 않는 이유: 토큰의 유효성은 보관소에 해시가 있는지로만 판정한다. 길이 · 문자 검사로 먼저
        // 거르면 판정이 두 곳으로 갈라지고, 무효 토큰이 400 AUTH_RESET_TOKEN_INVALID 가 아닌 INVALID_REQUEST 로 나간다.
        @NotBlank
        String token,

        @NotBlank
        @PasswordPolicy
        String newPassword
) {
}
