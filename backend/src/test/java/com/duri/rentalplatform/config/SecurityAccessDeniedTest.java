package com.duri.rentalplatform.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증은 되었으나 권한이 모자란 요청의 403 응답을 검증한다.
 *
 * <p>{@link SecurityConfig}의 인가 규칙은 지금 {@code permitAll}과 {@code authenticated} 둘뿐이라
 * 인증된 요청을 거부하는 경로가 없다. 그래서 접근 거부를 {@link AccessDeniedException}으로 일으켜
 * {@code exceptionHandling}에 등록된 처리기가 공통 봉투(API 명세서 공통 규약 §1.2)로 응답하는지 본다.
 *
 * <p>{@link GlobalExceptionHandler}를 슬라이스에서 제외한다. 실제 인가 거부는 컨트롤러 밖(인가 필터)에서
 * 발생해 전역 처리기를 거치지 않는데, 시험용 예외는 컨트롤러 안에서 던져야 해서 그대로 두면 전역 처리기가 먼저
 * 잡아 500으로 바꾼다. 제외해야 필터 단계의 거부와 같은 경로가 된다.
 *
 * <p>같은 이유로 <b>메서드 보안({@code @PreAuthorize})의 거부도 컨트롤러 안에서 발생</b>하므로 전역 처리기가
 * 500으로 바꾼다. 메서드 보안을 켜는 슬라이스는 이 지점을 함께 처리해야 한다.
 */
@WebMvcTest(excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = GlobalExceptionHandler.class))
@Import({
    SecurityConfig.class,
    SecurityAccessDeniedTest.TokenProviderTestConfig.class,
    SecurityAccessDeniedTest.DeniedProbeController.class
})
class SecurityAccessDeniedTest {

    private static final String SECRET = "security-config-test-secret-".repeat(3);

    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));

    private static final String DENIED_PATH = "/api/me/denied";

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("권한이 모자란 요청은 403 AUTH_FORBIDDEN을 공통 봉투로 반환한다")
    void accessDenied() throws Exception {
        String accessToken = TOKENS.createAccessToken(42L, "USER");

        mockMvc.perform(get(DENIED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("접근 권한이 없습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("토큰 없이 같은 경로를 부르면 403이 아니라 401이다 — 인증 실패와 권한 부족을 섞지 않는다")
    void unauthenticatedIsNotForbidden() throws Exception {
        mockMvc.perform(get(DENIED_PATH))
                .andExpect(status().is(401))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));
    }

    @TestConfiguration
    static class TokenProviderTestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return TOKENS;
        }
    }

    /** 인가 필터가 거부한 것과 같은 예외를 던지는 시험용 엔드포인트. */
    @RestController
    static class DeniedProbeController {

        @GetMapping(DENIED_PATH)
        ApiResponse<Void> denied(@AuthenticationPrincipal Long userId) {
            throw new AccessDeniedException("권한이 없는 사용자: " + userId);
        }
    }
}
