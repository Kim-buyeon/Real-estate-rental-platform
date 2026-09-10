package com.duri.rentalplatform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 필터체인의 경로 인가와 인증 실패 응답을 실제 요청으로 검증한다.
 *
 * <p>{@link SecurityConfig}를 그대로 올리고 {@link JwtTokenProvider}만 고정 키·고정 만료로 갈아 끼운다.
 * 데이터베이스는 관여하지 않으므로 컨트롤러 슬라이스로 충분하다(testing.md §1.1).
 *
 * <p>운영 경로에는 아직 컨트롤러가 없다. 인가 규칙이 보는 것은 경로이므로, 실제 경로 모양({@code /api/auth/**},
 * {@code /api/me/**})에 시험용 엔드포인트를 매달아 상태 코드로 판정한다.
 *
 * <p>확인하는 것은 네 가지다. 첫째, 보호 경로는 토큰 없이 뚫리지 않는다. 둘째, 실패 사유가 만료
 * ({@code AUTH_TOKEN_EXPIRED})와 무효({@code AUTH_INVALID_CREDENTIAL})로 갈린다. 셋째, 실패 응답이
 * 스프링 기본 본문이 아니라 공통 봉투(API 명세서 공통 규약 §1.2)다. 넷째, 통과한 요청의 인증에 실리는
 * 권한 문자열이다 — 관리자 전용 경로(API 명세서 공통 규약 §1.5)의 인가가 이 문자열을 보고 판단하므로
 * 접두와 role 값을 모두 고정해 둔다.
 */
@WebMvcTest
@Import({
    SecurityConfig.class,
    SecurityConfigTest.TokenProviderTestConfig.class,
    SecurityConfigTest.ProbeController.class
})
class SecurityConfigTest {

    private static final String SECRET = "security-config-test-secret-".repeat(3);

    /** 정상 발급용. */
    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));

    /** 만료 토큰 생성용. 실제로 기다리지 않고 만료 시간을 음수로 준다. */
    private static final JwtTokenProvider EXPIRED_TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofSeconds(-1), Duration.ofSeconds(-1));

    private static final String PUBLIC_PATH = "/api/auth/probe";
    private static final String PROTECTED_PATH = "/api/me/probe";
    private static final String AUTHORITIES_PATH = "/api/me/probe/authorities";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("토큰 없이 보호 경로를 부르면 401 AUTH_INVALID_CREDENTIAL을 공통 봉투로 반환한다")
    void noToken() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH))
                .andExpect(status().is(401))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"))
                .andExpect(jsonPath("$.error.message").value("인증 정보가 일치하지 않습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("본문이 바뀐 토큰은 401 AUTH_INVALID_CREDENTIAL이다")
    void tamperedToken() throws Exception {
        String[] issued = TOKENS.createAccessToken(42L, "USER").split("\\.");
        String[] other = TOKENS.createAccessToken(43L, "ADMIN").split("\\.");
        String tampered = issued[0] + "." + other[1] + "." + issued[2];

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().is(401))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("만료된 토큰은 401 AUTH_TOKEN_EXPIRED로 무효 토큰과 구분된다")
    void expiredToken() throws Exception {
        String expired = EXPIRED_TOKENS.createAccessToken(42L, "USER");

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().is(401))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.error.message").value("액세스 토큰이 만료되었습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("리프레시 토큰을 액세스 토큰 자리에 넣으면 401 AUTH_INVALID_CREDENTIAL이다")
    void refreshTokenInAccessSlot() throws Exception {
        String refreshToken = TOKENS.createRefreshToken(42L);

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
                .andExpect(status().is(401))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));
    }

    @Test
    @DisplayName("Bearer 형식이 아닌 Authorization 헤더는 토큰 없음과 같이 401이다")
    void nonBearerHeader() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().is(401))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));
    }

    @Test
    @DisplayName("유효한 토큰이면 통과하고 컨트롤러가 사용자 식별자를 받는다")
    void validToken() throws Exception {
        String accessToken = TOKENS.createAccessToken(42L, "USER");

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().is(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(42))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @ParameterizedTest(name = "role={0} 이면 권한은 {1} 하나다")
    @CsvSource({"USER, ROLE_USER", "ADMIN, ROLE_ADMIN"})
    @DisplayName("인증에 실리는 권한은 ROLE_ 접두를 붙인 토큰의 role 값 하나다")
    void grantedAuthority(String role, String expectedAuthority) throws Exception {
        String accessToken = TOKENS.createAccessToken(42L, role);

        mockMvc.perform(get(AUTHORITIES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().is(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0]").value(expectedAuthority));
    }

    @Test
    @DisplayName("공개 경로 /api/auth/**는 토큰 없이 통과한다")
    void publicPathWithoutToken() throws Exception {
        mockMvc.perform(get(PUBLIC_PATH))
                .andExpect(status().is(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("public"));
    }

    @Test
    @DisplayName("공개 경로는 만료된 토큰을 들고 와도 막히지 않는다 — 재발급 호출이 차단되면 안 된다")
    void publicPathWithExpiredToken() throws Exception {
        String expired = EXPIRED_TOKENS.createAccessToken(42L, "USER");

        mockMvc.perform(get(PUBLIC_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().is(200))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PasswordEncoder는 같은 평문을 매번 다르게 인코딩하고 대조는 통과시킨다")
    void passwordEncoderUsesSalt() {
        String raw = "duri-Password-1234";

        String first = passwordEncoder.encode(raw);
        String second = passwordEncoder.encode(raw);

        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotContain(raw);
        assertThat(passwordEncoder.matches(raw, first)).isTrue();
        assertThat(passwordEncoder.matches(raw, second)).isTrue();
    }

    @Test
    @DisplayName("PasswordEncoder는 다른 평문을 통과시키지 않는다")
    void passwordEncoderRejectsWrongPassword() {
        String encoded = passwordEncoder.encode("duri-Password-1234");

        assertThat(passwordEncoder.matches("duri-Password-1235", encoded)).isFalse();
        assertThat(passwordEncoder.matches("", encoded)).isFalse();
    }

    /** 컨텍스트에는 고정 키·고정 만료의 제공자를 넣는다. 실행 환경의 {@code JWT_SECRET}에 의존하지 않는다. */
    @TestConfiguration
    static class TokenProviderTestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return TOKENS;
        }
    }

    /** 운영 경로 모양만 빌린 시험용 엔드포인트. 인가 규칙이 보는 것은 경로다. */
    @RestController
    static class ProbeController {

        @GetMapping(PUBLIC_PATH)
        ApiResponse<String> publicProbe() {
            return ApiResponse.ok("public");
        }

        @GetMapping(PROTECTED_PATH)
        ApiResponse<Long> protectedProbe(@AuthenticationPrincipal Long userId) {
            return ApiResponse.ok(userId);
        }

        /** 필터가 채운 권한을 그대로 돌려준다. 관리자 등급 인가가 이 문자열 위에 세워진다. */
        @GetMapping(AUTHORITIES_PATH)
        ApiResponse<List<String>> authoritiesProbe(Authentication authentication) {
            return ApiResponse.ok(authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList());
        }
    }
}
