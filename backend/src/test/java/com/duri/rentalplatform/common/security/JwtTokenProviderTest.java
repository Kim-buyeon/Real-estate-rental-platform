package com.duri.rentalplatform.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.security.JwtTokenProvider.TokenClaims;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 토큰 발급·검증의 단위 검증. 스프링 컨텍스트도 데이터베이스도 쓰지 않는다(testing.md §1.1 단위 테스트).
 *
 * <p>핵심은 실패 사유의 구분이다. 만료는 {@code AUTH_TOKEN_EXPIRED}로 재발급을 유도해야 하고, 그 밖의 무효는
 * 모두 {@code AUTH_INVALID_CREDENTIAL}이어야 한다(API 명세서 공통 규약 §2). 하나라도 뒤섞이면 클라이언트가
 * 재발급해야 할 때 로그아웃시키거나, 위조 토큰에 재발급을 시도한다.
 *
 * <p>만료 토큰은 만료 시간을 음수로 준 별도 인스턴스로 만든다. 실제로 기다리면 테스트가 그 시간만큼 느려진다.
 */
class JwtTokenProviderTest {

    /** HS512 서명 실험까지 하므로 512비트(64바이트) 이상으로 둔다. 운영 키는 .env가 갖는다. */
    private static final String SECRET = "security-config-test-secret-".repeat(3);

    private static final Duration ACCESS_VALIDITY = Duration.ofMinutes(30);
    private static final Duration REFRESH_VALIDITY = Duration.ofDays(14);

    private final JwtTokenProvider provider =
            new JwtTokenProvider(SECRET, ACCESS_VALIDITY, REFRESH_VALIDITY);

    @Test
    @DisplayName("발급한 액세스 토큰을 검증하면 사용자 식별자와 권한이 그대로 돌아온다")
    void accessTokenRoundTrip() {
        String token = provider.createAccessToken(42L, "USER");

        TokenClaims claims = provider.parseAccessToken(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.role()).isEqualTo("USER");
    }

    @Test
    @DisplayName("만료된 액세스 토큰은 AUTH_TOKEN_EXPIRED로 구분된다")
    void expiredAccessToken() {
        JwtTokenProvider expiredProvider =
                new JwtTokenProvider(SECRET, Duration.ofSeconds(-1), REFRESH_VALIDITY);
        String token = expiredProvider.createAccessToken(42L, "USER");

        assertThatThrownBy(() -> provider.parseAccessToken(token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("만료 직전 토큰은 통과한다 — 만료 판정의 경계")
    void notYetExpiredAccessToken() {
        JwtTokenProvider almostExpiredProvider =
                new JwtTokenProvider(SECRET, Duration.ofSeconds(30), REFRESH_VALIDITY);
        String token = almostExpiredProvider.createAccessToken(42L, "USER");

        assertThat(provider.parseAccessToken(token).userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("본문이 바뀐 토큰은 서명이 맞지 않아 AUTH_INVALID_CREDENTIAL이다")
    void tamperedToken() {
        String[] issued = provider.createAccessToken(42L, "USER").split("\\.");
        String[] other = provider.createAccessToken(43L, "ADMIN").split("\\.");
        // 다른 사용자의 본문에 원래 서명을 붙인다. 서명 검증이 없으면 43번 관리자로 통과한다.
        String tampered = issued[0] + "." + other[1] + "." + issued[2];

        assertErrorCode(tampered, ErrorCode.AUTH_INVALID_CREDENTIAL);
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 AUTH_INVALID_CREDENTIAL이다")
    void tokenSignedWithAnotherKey() {
        JwtTokenProvider foreignProvider =
                new JwtTokenProvider("another-secret-key-".repeat(4), ACCESS_VALIDITY, REFRESH_VALIDITY);
        String token = foreignProvider.createAccessToken(42L, "USER");

        assertErrorCode(token, ErrorCode.AUTH_INVALID_CREDENTIAL);
    }

    @Test
    @DisplayName("리프레시 토큰을 액세스 토큰 자리에 쓰면 AUTH_INVALID_CREDENTIAL이다")
    void refreshTokenRejectedAsAccessToken() {
        String refreshToken = provider.createRefreshToken(42L);

        assertErrorCode(refreshToken, ErrorCode.AUTH_INVALID_CREDENTIAL);
    }

    @Test
    @DisplayName("권한 클레임까지 갖춘 리프레시 토큰도 종류가 REFRESH면 거부된다 — 토큰 종류 검사")
    void refreshTypeIsRejectedEvenWithRole() throws Exception {
        // 발급된 리프레시 토큰은 권한 클레임이 없어 어차피 거부된다. 종류 검사만 남기려면 나머지를 다 채워야 한다.
        String token = signWith(
                JWSAlgorithm.HS256,
                new JWTClaimsSet.Builder()
                        .subject("42")
                        .claim("tokenType", "REFRESH")
                        .claim("role", "USER")
                        .expirationTime(Date.from(Instant.now().plus(ACCESS_VALIDITY)))
                        .build());

        assertErrorCode(token, ErrorCode.AUTH_INVALID_CREDENTIAL);
    }

    @Test
    @DisplayName("같은 키로 다른 알고리즘(HS512)에 서명해도 AUTH_INVALID_CREDENTIAL이다")
    void tokenSignedWithAnotherAlgorithm() throws Exception {
        String token = signWith(
                JWSAlgorithm.HS512,
                new JWTClaimsSet.Builder()
                        .subject("42")
                        .claim("tokenType", "ACCESS")
                        .claim("role", "USER")
                        .expirationTime(Date.from(Instant.now().plus(ACCESS_VALIDITY)))
                        .build());

        assertErrorCode(token, ErrorCode.AUTH_INVALID_CREDENTIAL);
    }

    @Test
    @DisplayName("만료 클레임이 없는 토큰은 만료가 아니라 AUTH_INVALID_CREDENTIAL이다")
    void tokenWithoutExpiration() throws Exception {
        String token = signWith(
                JWSAlgorithm.HS256,
                new JWTClaimsSet.Builder()
                        .subject("42")
                        .claim("tokenType", "ACCESS")
                        .claim("role", "USER")
                        .build());

        assertErrorCode(token, ErrorCode.AUTH_INVALID_CREDENTIAL);
    }

    @Test
    @DisplayName("권한 클레임이 없는 액세스 토큰은 AUTH_INVALID_CREDENTIAL이다")
    void tokenWithoutRole() throws Exception {
        String token = signWith(
                JWSAlgorithm.HS256,
                new JWTClaimsSet.Builder()
                        .subject("42")
                        .claim("tokenType", "ACCESS")
                        .expirationTime(Date.from(Instant.now().plus(ACCESS_VALIDITY)))
                        .build());

        assertErrorCode(token, ErrorCode.AUTH_INVALID_CREDENTIAL);
    }

    @Test
    @DisplayName("JWT 형식이 아닌 문자열은 AUTH_INVALID_CREDENTIAL이다")
    void malformedToken() {
        assertErrorCode("not-a-jwt", ErrorCode.AUTH_INVALID_CREDENTIAL);
    }

    @Test
    @DisplayName("HS256 최소 길이에 미달하는 키(31바이트)로는 생성 자체가 실패한다")
    void shortSecretFailsFast() {
        String tooShort = "a".repeat(31);

        assertThatThrownBy(() -> new JwtTokenProvider(tooShort, ACCESS_VALIDITY, REFRESH_VALIDITY))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(KeyLengthException.class);
    }

    @Test
    @DisplayName("HS256 최소 길이인 키(32바이트)로는 생성된다 — 키 길이 경계")
    void minimumLengthSecretIsAccepted() {
        String minimum = "a".repeat(32);

        assertThatCode(() -> new JwtTokenProvider(minimum, ACCESS_VALIDITY, REFRESH_VALIDITY))
                .doesNotThrowAnyException();
    }

    private void assertErrorCode(String token, ErrorCode expected) {
        assertThatThrownBy(() -> provider.parseAccessToken(token))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private String signWith(JWSAlgorithm algorithm, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
