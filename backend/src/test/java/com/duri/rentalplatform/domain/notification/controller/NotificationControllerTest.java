package com.duri.rentalplatform.domain.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.notification.dto.response.NotificationPageResponse;
import com.duri.rentalplatform.domain.notification.dto.response.NotificationResponse;
import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.service.NotificationQueryService;
import com.duri.rentalplatform.domain.notification.service.NotificationReadCommandService;
import com.duri.rentalplatform.domain.notification.store.StreamTicketStore;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

/**
 * {@link NotificationController} 의 인증 「필수」 · 상태 코드 · 응답 형태 · 입력 검증. 서비스는 목킹하고 {@link SecurityConfig} 를
 * 실제로 올린다.
 */
@WebMvcTest(controllers = NotificationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, NotificationControllerTest.TokenProviderTestConfig.class})
class NotificationControllerTest {

    private static final String SECRET = "notification-controller-test-secret-".repeat(3);
    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
    private static final long USER_ID = 42L;
    private static final String PATH = "/api/notifications";

    /** 실시간 수신 티켓을 소비하는 보안 체인의 의존. 이 슬라이스는 쓰지 않는다. */
    @MockitoBean
    StreamTicketStore streamTicketStore;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationQueryService queryService;

    @MockitoBean
    NotificationReadCommandService readCommandService;

    @Test
    @DisplayName("GET 은 명세 1.3 예시 형태(items · nextCursor · hasNext · unreadCount)로 200 이고 토큰의 사용자로 조회한다")
    void listMatchesSpec() throws Exception {
        when(queryService.findByUser(eq(USER_ID), any())).thenReturn(new NotificationPageResponse(List.of(
                new NotificationResponse(9012L, NotificationType.RISK_CHANGE, NotificationType.RISK_CHANGE.getTitle(),
                        1024L, "CAUTION", "DANGER", false,
                        OffsetDateTime.of(2026, 7, 29, 3, 5, 0, 0, ZoneOffset.ofHours(9)))),
                "eyJpZCI6OTAxMn0", true, 4L));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].notificationId").value(9012))
                .andExpect(jsonPath("$.data.items[0].type").value("RISK_CHANGE"))
                .andExpect(jsonPath("$.data.items[0].title").value("관심 매물의 위험 등급이 변경되었습니다"))
                .andExpect(jsonPath("$.data.items[0].propertyId").value(1024))
                .andExpect(jsonPath("$.data.items[0].beforeValue").value("CAUTION"))
                .andExpect(jsonPath("$.data.items[0].afterValue").value("DANGER"))
                .andExpect(jsonPath("$.data.items[0].isRead").value(false))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-07-29T03:05:00+09:00"))
                .andExpect(jsonPath("$.data.nextCursor").value("eyJpZCI6OTAxMn0"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.unreadCount").value(4));
    }

    @Test
    @DisplayName("size 가 100 을 넘으면 400 INVALID_REQUEST")
    void sizeOverMaxIsBadRequest() throws Exception {
        mockMvc.perform(get(PATH).param("size", "101").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("개별 읽음은 200 에 data 없음, 토큰의 사용자로 처리한다")
    void markReadReturnsOkWithoutData() throws Exception {
        mockMvc.perform(patch(PATH + "/9012/read").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(readCommandService).markRead(USER_ID, 9012L);
    }

    @Test
    @DisplayName("없거나 남의 알림이면 404 NOTIFICATION_NOT_FOUND")
    void markReadNotFound() throws Exception {
        doThrow(new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .when(readCommandService).markRead(USER_ID, 9012L);

        mockMvc.perform(patch(PATH + "/9012/read").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("전체 읽음은 200 에 data 없음")
    void markAllReadReturnsOk() throws Exception {
        mockMvc.perform(patch(PATH + "/read-all").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(readCommandService).markAllRead(USER_ID);
    }

    @Test
    @DisplayName("토큰 없으면 세 경로 모두 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(PATH + "/9012/read")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(PATH + "/read-all")).andExpect(status().isUnauthorized());
        verifyNoInteractions(queryService, readCommandService);
    }

    private static String bearer() {
        return "Bearer " + TOKENS.createAccessToken(USER_ID, "USER");
    }

    @TestConfiguration
    static class TokenProviderTestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return TOKENS;
        }
    }
}
