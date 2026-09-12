package com.duri.rentalplatform.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/auth/reissue} 액세스 토큰 재발급 요청.
 */
public record ReissueRequest(

        // 길이나 형식 제약을 두지 않는 이유: 토큰의 유효성은 서명과 만료로 판정하며
        // 그것은 JwtTokenProvider 의 몫이다. 여기서 길이를 재면 서명 검증과 판정이 두 곳으로 갈라진다.
        @NotBlank
        String refreshToken
) {
}
