package com.duri.rentalplatform.common.security;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 액세스·리프레시 토큰의 발급과 검증을 함께 담당한다.
 *
 * <p>서명은 대칭 키 HS256이다. 키는 {@code JWT_SECRET}으로 주입받고 만료 시간은 {@code application.yml}이
 * 갖는다 — 만료 시간은 비밀도 환경 차이도 아니기 때문이다.
 *
 * <p>Nimbus JOSE + JWT를 직접 쓴다. Spring Security의 {@code NimbusJwtDecoder}는 만료와 그 밖의 무효를
 * 같은 예외로 던져 설명 문자열로만 구분되는데, 명세가 만료(AUTH_TOKEN_EXPIRED)와 무효(AUTH_INVALID_CREDENTIAL)를
 * 다른 코드로 요구하므로 서명 검증과 만료 판정을 여기서 나눠 수행한다.
 */
@Component
public class JwtTokenProvider {

    private static final JWSAlgorithm SIGNATURE_ALGORITHM = JWSAlgorithm.HS256;

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String TOKEN_TYPE_ACCESS = "ACCESS";
    private static final String TOKEN_TYPE_REFRESH = "REFRESH";

    private final JWSSigner signer;
    private final JWSVerifier verifier;
    private final Duration accessTokenValidity;
    private final Duration refreshTokenValidity;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity}") Duration accessTokenValidity,
            @Value("${jwt.refresh-token-validity}") Duration refreshTokenValidity) {
        byte[] secretKey = secret.getBytes(StandardCharsets.UTF_8);
        try {
            this.signer = new MACSigner(secretKey);
            this.verifier = new MACVerifier(secretKey);
        } catch (KeyLengthException e) {
            // 짧은 키로 기동하면 서명이 약해진 채 운영에 들어간다. 첫 요청이 아니라 기동에서 막는다.
            throw new IllegalStateException("JWT 서명 키가 HS256 최소 길이(256비트)에 미치지 못합니다.", e);
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT 서명 키를 초기화하지 못했습니다.", e);
        }
        this.accessTokenValidity = accessTokenValidity;
        this.refreshTokenValidity = refreshTokenValidity;
    }

    /** 액세스 토큰을 발급한다. 사용자 식별자와 권한을 클레임에 담는다. */
    public String createAccessToken(Long userId, String role) {
        JWTClaimsSet claims = claimsOf(userId, accessTokenValidity)
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .claim(CLAIM_ROLE, role)
                .build();
        return sign(claims);
    }

    /** 리프레시 토큰을 발급한다. 재발급 외의 용도가 없으므로 권한을 담지 않는다. */
    public String createRefreshToken(Long userId) {
        JWTClaimsSet claims = claimsOf(userId, refreshTokenValidity)
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH)
                .build();
        return sign(claims);
    }

    /**
     * 액세스 토큰을 검증하고 인증 주체를 꺼낸다.
     *
     * @throws BusinessException 만료면 {@code AUTH_TOKEN_EXPIRED}, 그 밖의 무효면 {@code AUTH_INVALID_CREDENTIAL}
     */
    public TokenClaims parseAccessToken(String token) {
        JWTClaimsSet claims = verifyAndExtract(token);

        // 리프레시 토큰이 액세스 토큰 자리에 그대로 통하면 권한 없는 주체가 인증된다.
        if (!TOKEN_TYPE_ACCESS.equals(claims.getClaim(CLAIM_TOKEN_TYPE))) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL);
        }

        Date expiresAt = claims.getExpirationTime();
        // 만료 클레임이 없는 토큰은 만료된 것이 아니라 형식이 잘못된 것이다. 재발급을 유도하지 않는다.
        if (expiresAt == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL);
        }
        if (expiresAt.toInstant().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }

        return toTokenClaims(claims);
    }

    private JWTClaimsSet.Builder claimsOf(Long userId, Duration validity) {
        Instant issuedAt = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(validity)));
    }

    private String sign(JWTClaimsSet claims) {
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(SIGNATURE_ALGORITHM), claims);
        try {
            signedJwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT 서명에 실패했습니다.", e);
        }
        return signedJwt.serialize();
    }

    private JWTClaimsSet verifyAndExtract(String token) {
        try {
            SignedJWT signedJwt = SignedJWT.parse(token);
            // 헤더의 알고리즘을 그대로 신뢰하면 알고리즘 혼동 공격에 열린다. 발급 때 쓴 것만 받는다.
            if (!SIGNATURE_ALGORITHM.equals(signedJwt.getHeader().getAlgorithm())
                    || !signedJwt.verify(verifier)) {
                throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL);
            }
            return signedJwt.getJWTClaimsSet();
        } catch (ParseException | JOSEException e) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL);
        }
    }

    private TokenClaims toTokenClaims(JWTClaimsSet claims) {
        String subject = claims.getSubject();
        Object role = claims.getClaim(CLAIM_ROLE);
        if (subject == null || !(role instanceof String roleName) || roleName.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL);
        }
        try {
            return new TokenClaims(Long.valueOf(subject), roleName);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL);
        }
    }

    /**
     * 액세스 토큰에서 꺼낸 인증 주체.
     *
     * <p>사용자 식별자와 권한만 담는다. 컨트롤러가 {@code @AuthenticationPrincipal Long userId}로 받으므로
     * 요청마다 사용자를 조회하지 않는다.
     */
    public record TokenClaims(Long userId, String role) {}
}
