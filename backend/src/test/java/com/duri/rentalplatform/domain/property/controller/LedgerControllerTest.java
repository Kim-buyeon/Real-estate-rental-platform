package com.duri.rentalplatform.domain.property.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.notification.store.StreamTicketStore;
import com.duri.rentalplatform.domain.property.dto.response.LedgerResponse;
import com.duri.rentalplatform.domain.property.service.LedgerCommandService;
import com.duri.rentalplatform.domain.property.service.LedgerQueryService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link LedgerController} 의 인가 · 응답 형태 · 오류 경로. 서비스는 목킹한다.
 *
 * <p>응답 형태는 API 명세서(매물) 1.8 예시와 필드 이름 · 형식을 대조한다. {@link SecurityConfig} 를 실제로 올려 인증
 * 「선택」 경로가 토큰 없이 열리는지 본다.
 */
@WebMvcTest(controllers = LedgerController.class)
@Import({
    SecurityConfig.class,
    GlobalExceptionHandler.class,
    LedgerControllerTest.TokenProviderTestConfig.class
})
class LedgerControllerTest {

    private static final String SECRET = "ledger-controller-test-secret-".repeat(3);

    /** 실시간 수신 티켓을 소비하는 보안 체인의 의존. 이 슬라이스는 쓰지 않는다. */
    @MockitoBean
    StreamTicketStore streamTicketStore;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LedgerCommandService commandService;

    @MockitoBean
    LedgerQueryService queryService;

    @Test
    @DisplayName("토큰 없이 조회하면 명세 1.8 예시와 같은 형태로 응답한다")
    void responseMatchesSpecExample() throws Exception {
        when(queryService.getLedger(1024L)).thenReturn(new LedgerResponse(1024L, "공동주택", true, false,
                new BigDecimal("480.2"), new BigDecimal("42.5"), LocalDate.of(2015, 4, 18),
                OffsetDateTime.of(2026, 7, 28, 2, 10, 0, 0, ZoneOffset.ofHours(9))));

        mockMvc.perform(get("/api/properties/1024/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.propertyId").value(1024))
                .andExpect(jsonPath("$.data.mainPurpose").value("공동주택"))
                .andExpect(jsonPath("$.data.isResidential").value(true))
                .andExpect(jsonPath("$.data.violationBuilding").value(false))
                .andExpect(jsonPath("$.data.totalFloorArea").value(480.2))
                .andExpect(jsonPath("$.data.exclusiveArea").value(42.5))
                .andExpect(jsonPath("$.data.approvalDate").value("2015-04-18"))
                .andExpect(jsonPath("$.data.collectedAt").value("2026-07-28T02:10:00+09:00"))
                .andExpect(jsonPath("$.data", aMapWithSize(8)));
    }

    @Test
    @DisplayName("수집을 먼저 부르고 조회를 부른다")
    void collectsBeforeQuery() throws Exception {
        mockMvc.perform(get("/api/properties/7/ledger"));

        InOrder order = inOrder(commandService, queryService);
        order.verify(commandService).collectIfAbsent(7L);
        order.verify(queryService).getLedger(7L);
    }

    @Test
    @DisplayName("매물이 없으면 404 PROPERTY_NOT_FOUND 이고 조회를 부르지 않는다")
    void propertyNotFound() throws Exception {
        doThrow(new BusinessException(ErrorCode.PROPERTY_NOT_FOUND)).when(commandService).collectIfAbsent(99L);

        mockMvc.perform(get("/api/properties/99/ledger"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PROPERTY_NOT_FOUND"));
        verify(queryService, never()).getLedger(any());
    }

    @Test
    @DisplayName("연동이 실패하면 503 EXTERNAL_API_UNAVAILABLE 이고 조회를 부르지 않는다")
    void externalApiUnavailable() throws Exception {
        doThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE)).when(commandService).collectIfAbsent(5L);

        mockMvc.perform(get("/api/properties/5/ledger"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("EXTERNAL_API_UNAVAILABLE"));
        verify(queryService, never()).getLedger(any());
    }

    @Test
    @DisplayName("propertyId 가 숫자가 아니면 400 INVALID_REQUEST")
    void nonNumericPropertyId() throws Exception {
        mockMvc.perform(get("/api/properties/abc/ledger"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @TestConfiguration
    static class TokenProviderTestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
        }
    }
}
