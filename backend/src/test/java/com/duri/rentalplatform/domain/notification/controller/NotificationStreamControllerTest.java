package com.duri.rentalplatform.domain.notification.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.notification.store.SseEmitterStore;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link NotificationStreamController} 의 인증 「필수」 — 헤더 토큰 · 쿼리 파라미터 토큰 · 헤더 우선 — 과 스트림 시작. 보관소는
 * 목킹하고 {@link SecurityConfig} 를 실제로 올린다.
 */
@WebMvcTest(controllers = NotificationStreamController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, NotificationStreamControllerTest.TokenProviderTestConfig.class})
class NotificationStreamControllerTest {

    private static final String SECRET = "notification-stream-test-secret-".repeat(3);
    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
    private static final long USER_ID = 42L;
    private static final String PATH = "/api/notifications/stream";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SseEmitterStore sseEmitterStore;

    @Test
    @DisplayName("헤더 토큰이면 토큰의 사용자로 연결을 열고 비동기 스트림을 시작한다")
    void headerTokenStartsStream() throws Exception {
        when(sseEmitterStore.connect(USER_ID)).thenReturn(new SseEmitter());

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(sseEmitterStore).connect(USER_ID);
    }

    @Test
    @DisplayName("헤더가 없으면 쿼리 파라미터 accessToken 으로 인증해 스트림을 시작한다")
    void queryTokenStartsStream() throws Exception {
        when(sseEmitterStore.connect(USER_ID)).thenReturn(new SseEmitter());

        mockMvc.perform(get(PATH).queryParam("accessToken", accessToken()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(sseEmitterStore).connect(USER_ID);
    }

    @Test
    @DisplayName("헤더 토큰이 무효면 쿼리 파라미터가 유효해도 401 AUTH_INVALID_CREDENTIAL — 헤더가 우선이다")
    void invalidHeaderIsNotRescuedByQueryToken() throws Exception {
        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
                        .queryParam("accessToken", accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));

        verifyNoInteractions(sseEmitterStore);
    }

    @Test
    @DisplayName("토큰이 없으면 401 이고 연결을 열지 않는다")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));

        verifyNoInteractions(sseEmitterStore);
    }

    private static String accessToken() {
        return TOKENS.createAccessToken(USER_ID, "USER");
    }

    @TestConfiguration
    static class TokenProviderTestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return TOKENS;
        }
    }
}
