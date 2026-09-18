package com.duri.rentalplatform.domain.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.notification.dto.response.StreamTicketResponse;
import com.duri.rentalplatform.domain.notification.service.NotificationStreamService;
import com.duri.rentalplatform.domain.notification.store.StreamTicketStore;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
 * {@link NotificationStreamController} 의 인증 「필수」와 스트림 시작. 서비스와 티켓 보관소는 목킹하고 {@link SecurityConfig} 를
 * 실제로 올린다.
 *
 * <p>확인하는 것은 <b>스트림을 여는 자격 증명이 무엇인가</b>다 — 일회용 티켓이거나 헤더의 Bearer 토큰이고, 쿼리 파라미터
 * {@code accessToken} 은 더 이상 인증이 아니다(API 명세서 알림 1.1). 티켓이 실제로 한 번만 소비되는 것은
 * {@code StreamTicketStoreTest} 가 Redis 에 대고 확인한다. 여기서는 소비에 실패한 티켓이 스트림을 열기 전에 401 봉투를 받는지 본다.
 */
@WebMvcTest(controllers = NotificationStreamController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, NotificationStreamControllerTest.TokenProviderTestConfig.class})
class NotificationStreamControllerTest {

    private static final String SECRET = "notification-stream-test-secret-".repeat(3);
    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
    private static final long USER_ID = 42L;
    private static final String PATH = "/api/notifications/stream";
    private static final String TICKET_PATH = "/api/notifications/stream-ticket";
    private static final String TICKET = "yl3Q9mQ2Zr8pK1s7";
    private static final Instant TOKEN_EXPIRES_AT =
            Instant.now().plusSeconds(1_200).truncatedTo(ChronoUnit.SECONDS);

    private static StreamTicketStore.TicketClaims claims() {
        return new StreamTicketStore.TicketClaims(USER_ID, TOKEN_EXPIRES_AT);
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationStreamService streamService;

    @MockitoBean
    StreamTicketStore streamTicketStore;

    @Test
    @DisplayName("티켓으로 티켓 주인의 연결을 열고 비동기 스트림을 시작한다")
    void ticketStartsStream() throws Exception {
        when(streamTicketStore.consume(TICKET)).thenReturn(Optional.of(claims()));
        when(streamService.connect(eq(USER_ID), any())).thenReturn(new SseEmitter());

        mockMvc.perform(get(PATH).queryParam("ticket", TICKET))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(streamService).connect(USER_ID, TOKEN_EXPIRES_AT);
    }

    @Test
    @DisplayName("티켓에 담긴 토큰 만료가 연결 수명으로 넘어간다 — 헤더 토큰 경로와 같은 자리다")
    void ticketCarriesTokenExpiryIntoConnectionLifetime() throws Exception {
        Instant almostExpired = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        when(streamTicketStore.consume(TICKET))
                .thenReturn(Optional.of(new StreamTicketStore.TicketClaims(USER_ID, almostExpired)));
        when(streamService.connect(eq(USER_ID), any())).thenReturn(new SseEmitter());

        mockMvc.perform(get(PATH).queryParam("ticket", TICKET))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        // 연결 시점부터 설정 상한(30분)이 아니라 토큰 잔여 시간이 수명의 입력이다.
        verify(streamService).connect(USER_ID, almostExpired);
    }

    @Test
    @DisplayName("토큰 만료를 모르는 티켓이면 연결 수명은 설정 상한을 쓴다")
    void ticketWithoutTokenExpiryLeavesLifetimeToMaxTimeout() throws Exception {
        when(streamTicketStore.consume(TICKET))
                .thenReturn(Optional.of(new StreamTicketStore.TicketClaims(USER_ID, null)));
        when(streamService.connect(eq(USER_ID), any())).thenReturn(new SseEmitter());

        mockMvc.perform(get(PATH).queryParam("ticket", TICKET))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(streamService).connect(eq(USER_ID), isNull());
    }

    @Test
    @DisplayName("같은 티켓을 두 번 쓰면 두 번째는 401 AUTH_INVALID_CREDENTIAL 이고 연결을 열지 않는다")
    void consumedTicketIsRejectedOnSecondUse() throws Exception {
        when(streamTicketStore.consume(TICKET))
                .thenReturn(Optional.of(claims()))
                .thenReturn(Optional.empty());
        when(streamService.connect(eq(USER_ID), any())).thenReturn(new SseEmitter());

        mockMvc.perform(get(PATH).queryParam("ticket", TICKET))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        mockMvc.perform(get(PATH).queryParam("ticket", TICKET))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));

        // 두 번째 요청은 연결을 열지 않았다 — 열린 연결은 첫 번째 하나뿐이다.
        verify(streamService).connect(USER_ID, TOKEN_EXPIRES_AT);
    }

    @Test
    @DisplayName("만료되었거나 모르는 티켓은 401 이고 스트림을 열기 전에 봉투로 응답한다")
    void expiredTicketIsUnauthorized() throws Exception {
        when(streamTicketStore.consume(TICKET)).thenReturn(Optional.empty());

        mockMvc.perform(get(PATH).queryParam("ticket", TICKET))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));

        verifyNoInteractions(streamService);
    }

    @Test
    @DisplayName("티켓이 없으면 401 이고 연결을 열지 않는다")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));

        verifyNoInteractions(streamService);
        verifyNoInteractions(streamTicketStore);
    }

    @Test
    @DisplayName("쿼리 파라미터 accessToken 은 유효한 액세스 토큰이어도 인증이 아니다")
    void accessTokenQueryParameterIsNotAccepted() throws Exception {
        mockMvc.perform(get(PATH).queryParam("accessToken", accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));

        verifyNoInteractions(streamService);
        verifyNoInteractions(streamTicketStore);
    }

    @Test
    @DisplayName("헤더 토큰이면 티켓 없이도 연결을 열고 토큰 만료를 연결 수명에 넘긴다")
    void headerTokenStartsStream() throws Exception {
        when(streamService.connect(eq(USER_ID), any())).thenReturn(new SseEmitter());

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(streamService).connect(eq(USER_ID), notNull());
        verifyNoInteractions(streamTicketStore);
    }

    @Test
    @DisplayName("헤더 토큰이 무효면 티켓을 보지 않고 401 — 헤더가 우선이다")
    void invalidHeaderIsNotRescuedByTicket() throws Exception {
        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
                        .queryParam("ticket", TICKET))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));

        verifyNoInteractions(streamService);
        verifyNoInteractions(streamTicketStore);
    }

    @Test
    @DisplayName("발급은 액세스 토큰으로 인증된 사용자에게 티켓을 주고, 그 토큰의 만료를 함께 넘긴다")
    void issuesTicketForAuthenticatedUser() throws Exception {
        when(streamService.issueTicket(eq(USER_ID), any())).thenReturn(new StreamTicketResponse(TICKET));

        mockMvc.perform(post(TICKET_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticket").value(TICKET));

        // 발급 요청을 인증한 토큰의 만료가 티켓에 담긴다 — 연결 수명이 세션을 넘지 않게 하는 값이다.
        verify(streamService).issueTicket(eq(USER_ID), notNull());
    }

    @Test
    @DisplayName("인증 없이 발급을 부르면 401 이고 티켓을 만들지 않는다")
    void anonymousCannotIssueTicket() throws Exception {
        mockMvc.perform(post(TICKET_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));

        verifyNoInteractions(streamService);
        verifyNoInteractions(streamTicketStore);
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
