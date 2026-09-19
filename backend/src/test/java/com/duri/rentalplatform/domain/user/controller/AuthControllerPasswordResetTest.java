package com.duri.rentalplatform.domain.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.common.security.StreamTicketStore;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.user.dto.request.PasswordResetConfirmRequest;
import com.duri.rentalplatform.domain.user.dto.request.PasswordResetRequest;
import com.duri.rentalplatform.domain.user.service.UserCommandService;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link AuthController} 의 비밀번호 재설정 두 경로 — API 명세(회원) 1.3. 서비스는 목킹한다.
 *
 * <p>{@link SecurityConfig} 를 실제로 올려 두 경로가 토큰 없이 열리는지(인증 「공개」) 함께 본다. 모든 요청을 토큰 없이 보낸다.
 *
 * <p><b>비밀번호 규칙 위반 시 토큰 미소비</b>는 여기서 확인한다. 토큰 소비는 서비스 안에서만 일어나므로 「서비스가 불리지 않았다」가
 * 곧 「토큰이 소비되지 않았다」다.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({
    SecurityConfig.class,
    GlobalExceptionHandler.class,
    AuthControllerPasswordResetTest.TokenProviderTestConfig.class
})
class AuthControllerPasswordResetTest {

    private static final String SECRET = "auth-controller-password-reset-test-secret-".repeat(2);
    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));

    private static final String REQUEST_PATH = "/api/auth/password-reset";
    private static final String CONFIRM_PATH = "/api/auth/password-reset/confirm";

    /** 명세 1.3 요청 예시. */
    private static final String SPEC_REQUEST = """
            { "email": "user@example.com" }
            """;

    /** 명세 1.3 확정 요청 예시. */
    private static final String SPEC_CONFIRM = """
            { "token": "Qm9nVXNlclJlc2V0VG9rZW5FeGFtcGxl", "newPassword": "N3wP@ssw0rd!" }
            """;

    /** 실시간 수신 티켓을 소비하는 보안 체인의 의존. 이 슬라이스는 쓰지 않는다. */
    @MockitoBean
    StreamTicketStore streamTicketStore;

    @MockitoBean
    UserCommandService commandService;

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("요청 — 토큰 없이 200 이고 data 가 없다. 서비스에 이메일을 넘긴다")
    void requestReturns200WithoutData() throws Exception {
        mockMvc.perform(post(REQUEST_PATH).contentType(MediaType.APPLICATION_JSON).content(SPEC_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());

        ArgumentCaptor<PasswordResetRequest> captor = ArgumentCaptor.forClass(PasswordResetRequest.class);
        verify(commandService).requestPasswordReset(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("요청 — 이메일 형식이 아니면 400 INVALID_REQUEST · field email 이고 서비스를 부르지 않는다")
    void requestWithMalformedEmailIsInvalid() throws Exception {
        mockMvc.perform(post(REQUEST_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"email\": \"not-an-email\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("email"));

        verify(commandService, never()).requestPasswordReset(any());
    }

    @Test
    @DisplayName("확정 — 토큰 없이 200 이고 data 가 없다. 서비스에 토큰과 새 비밀번호를 넘긴다")
    void confirmReturns200WithoutData() throws Exception {
        mockMvc.perform(post(CONFIRM_PATH).contentType(MediaType.APPLICATION_JSON).content(SPEC_CONFIRM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());

        ArgumentCaptor<PasswordResetConfirmRequest> captor =
                ArgumentCaptor.forClass(PasswordResetConfirmRequest.class);
        verify(commandService).confirmPasswordReset(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new PasswordResetConfirmRequest("Qm9nVXNlclJlc2V0VG9rZW5FeGFtcGxl", "N3wP@ssw0rd!"));
    }

    @Test
    @DisplayName("확정 — 토큰이 무효면 400 AUTH_RESET_TOKEN_INVALID")
    void confirmWithInvalidTokenIs400() throws Exception {
        doThrow(new BusinessException(ErrorCode.AUTH_RESET_TOKEN_INVALID))
                .when(commandService).confirmPasswordReset(any());

        mockMvc.perform(post(CONFIRM_PATH).contentType(MediaType.APPLICATION_JSON).content(SPEC_CONFIRM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("확정 — 새 비밀번호가 규칙(8자 이상)을 어기면 400 INVALID_REQUEST · field newPassword 이고 토큰을 소비하지 않는다")
    void confirmWithPolicyViolationDoesNotConsumeToken() throws Exception {
        mockMvc.perform(post(CONFIRM_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(SPEC_CONFIRM.replace("N3wP@ssw0rd!", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("newPassword"));

        verify(commandService, never()).confirmPasswordReset(any());
    }

    @Test
    @DisplayName("확정 — 새 비밀번호가 UTF-8 72바이트를 넘으면 400 INVALID_REQUEST · field newPassword 이고 토큰을 소비하지 않는다")
    void confirmWithTooManyBytesDoesNotConsumeToken() throws Exception {
        // 한글 25자 = 75바이트. 문자 수(64)는 통과하지만 bcrypt 입력 한계를 넘는다 — PasswordPolicy 주석.
        mockMvc.perform(post(CONFIRM_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(SPEC_CONFIRM.replace("N3wP@ssw0rd!", "가".repeat(25))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("newPassword"));

        verify(commandService, never()).confirmPasswordReset(any());
    }

    @TestConfiguration
    static class TokenProviderTestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return TOKENS;
        }
    }
}
