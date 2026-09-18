package com.duri.rentalplatform.domain.risk.controller;

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
import com.duri.rentalplatform.domain.risk.dto.response.RegistryResponse;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.RegistryDataSource;
import com.duri.rentalplatform.domain.risk.service.RegistryCommandService;
import com.duri.rentalplatform.domain.risk.service.RegistryQueryService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * {@link RegistryController} 의 인가 · 응답 형태 · 오류 경로. 서비스는 목킹한다.
 *
 * <p>응답 형태는 API 명세서(위험도 분석) 1.3 예시와 필드 이름 · 중첩 · 형식을 대조한다. {@link SecurityConfig}
 * 를 실제로 올려 인증 「선택」 경로가 토큰 없이 열리는지 본다.
 */
@WebMvcTest(controllers = RegistryController.class)
@Import({
    SecurityConfig.class,
    GlobalExceptionHandler.class,
    RegistryControllerTest.TokenProviderTestConfig.class
})
class RegistryControllerTest {

    private static final String SECRET = "registry-controller-test-secret-".repeat(3);

    /** 실시간 수신 티켓을 소비하는 보안 체인의 의존. 이 슬라이스는 쓰지 않는다. */
    @MockitoBean
    StreamTicketStore streamTicketStore;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RegistryCommandService commandService;

    @MockitoBean
    RegistryQueryService queryService;

    @Test
    @DisplayName("토큰 없이 조회하면 명세 1.3 예시와 같은 형태로 응답한다")
    void responseMatchesSpecExample() throws Exception {
        when(queryService.getRegistry(1024L)).thenReturn(new RegistryResponse(
                1024L,
                List.of(new RegistryResponse.Ownership(2, OwnershipRightType.OWNERSHIP_TRANSFER, "김임대",
                        LocalDate.of(2019, 3, 11), "매매", true)),
                List.of(new RegistryResponse.Mortgage(1, "○○은행", 250_000_000L, LocalDate.of(2019, 3, 11), true)),
                OffsetDateTime.of(2026, 7, 29, 3, 0, 0, 0, ZoneOffset.ofHours(9)),
                RegistryDataSource.MOCK));

        mockMvc.perform(get("/api/properties/1024/registry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.propertyId").value(1024))
                .andExpect(jsonPath("$.data.ownerships[0].rankNo").value(2))
                .andExpect(jsonPath("$.data.ownerships[0].rightType").value("OWNERSHIP_TRANSFER"))
                .andExpect(jsonPath("$.data.ownerships[0].holderName").value("김임대"))
                .andExpect(jsonPath("$.data.ownerships[0].receivedDate").value("2019-03-11"))
                .andExpect(jsonPath("$.data.ownerships[0].cause").value("매매"))
                .andExpect(jsonPath("$.data.ownerships[0].isActive").value(true))
                .andExpect(jsonPath("$.data.ownerships[0]", aMapWithSize(6)))
                .andExpect(jsonPath("$.data.mortgages[0].rankNo").value(1))
                .andExpect(jsonPath("$.data.mortgages[0].creditor").value("○○은행"))
                .andExpect(jsonPath("$.data.mortgages[0].maxClaimAmount").value(250_000_000))
                .andExpect(jsonPath("$.data.mortgages[0].receivedDate").value("2019-03-11"))
                .andExpect(jsonPath("$.data.mortgages[0].isActive").value(true))
                .andExpect(jsonPath("$.data.mortgages[0]", aMapWithSize(5)))
                .andExpect(jsonPath("$.data.collectedAt").value("2026-07-29T03:00:00+09:00"))
                .andExpect(jsonPath("$.data.dataSource").value("MOCK"))
                .andExpect(jsonPath("$.data", aMapWithSize(5)));
    }

    @Test
    @DisplayName("수집을 먼저 부르고 조회를 부른다")
    void collectsBeforeQuery() throws Exception {
        mockMvc.perform(get("/api/properties/7/registry"));

        InOrder order = inOrder(commandService, queryService);
        order.verify(commandService).collectIfAbsent(7L);
        order.verify(queryService).getRegistry(7L);
    }

    @Test
    @DisplayName("매물이 없으면 404 PROPERTY_NOT_FOUND 이고 조회를 부르지 않는다")
    void propertyNotFound() throws Exception {
        doThrow(new BusinessException(ErrorCode.PROPERTY_NOT_FOUND)).when(commandService).collectIfAbsent(99L);

        mockMvc.perform(get("/api/properties/99/registry"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PROPERTY_NOT_FOUND"));
        verify(queryService, never()).getRegistry(any());
    }

    @Test
    @DisplayName("propertyId 가 숫자가 아니면 400 INVALID_REQUEST")
    void nonNumericPropertyId() throws Exception {
        mockMvc.perform(get("/api/properties/abc/registry"))
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
