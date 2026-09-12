package com.duri.rentalplatform.domain.user.dto.response;

/**
 * {@code POST /api/auth/login} · {@code POST /api/auth/reissue} 토큰 발급 응답.
 */
public record TokenResponse(

        String accessToken,

        String refreshToken,

        String tokenType,

        long expiresIn,

        // 항상 거짓인 이유: 참이 되는 경로는 소셜 인증으로 처음 가입하는 경우인데 그것이 차기 범위다.
        // 그래서 지금은 상수다. 명세의 공통 응답에 있는 필드이므로, 쓰이지 않는다고 지우면 명세와 어긋난다.
        boolean isNewUser
) {

    private static final String TOKEN_TYPE_BEARER = "Bearer";

    private static final boolean IS_NEW_USER = false;

    /**
     * 발급된 토큰 값으로 응답을 만든다. 고정값인 토큰 유형과 신규 가입 여부는 여기서 채운다.
     */
    public static TokenResponse of(String accessToken, String refreshToken, long expiresIn) {
        return new TokenResponse(accessToken, refreshToken, TOKEN_TYPE_BEARER, expiresIn, IS_NEW_USER);
    }
}
