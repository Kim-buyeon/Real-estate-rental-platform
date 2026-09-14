package com.duri.rentalplatform.domain.admin.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.admin.dto.response.GuaranteeCriteriaResponse;
import com.duri.rentalplatform.domain.admin.dto.response.LoanRegulationsResponse;
import com.duri.rentalplatform.domain.admin.service.CriteriaCommandService;
import com.duri.rentalplatform.domain.admin.service.CriteriaQueryService;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link CriteriaController} 의 인증 「관리자」 · 응답 형태 · 입력 검증. 서비스는 목킹하고 {@link SecurityConfig} 를
 * 실제로 올린다.
 */
@WebMvcTest(controllers = CriteriaController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, CriteriaControllerTest.TokenProviderTestConfig.class})
class CriteriaControllerTest {

    private static final String SECRET = "criteria-controller-test-secret-".repeat(3);
    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
    private static final long ADMIN_ID = 7L;
    private static final String GUARANTEE = "/api/admin/criteria/guarantee";

    private static final String SPEC_PUT = """
            {
              "collateralRatio": 90.0,
              "maxDeposit": 500000000,
              "requiresLoanLink": false,
              "changeReason": "보증금 한도 예시 — 비수도권 5억 적용"
            }
            """;

    private static final String LOAN_REGULATIONS = "/api/admin/criteria/loan-regulations";

    private static final String LOAN_PUT = """
            {
              "regulations": [
                {
                  "regulationId": 1,
                  "depositRatioLimit": 80.00,
                  "guaranteeCapNoHouse": 400000000,
                  "guaranteeCapOneHouse": 180000000,
                  "dsrLimit": 40.00,
                  "stressDsrRate": 3.00,
                  "dtiLimit": 40.00
                }
              ],
              "changeReason": "대출 규제 개정 반영"
            }
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CriteriaQueryService queryService;

    @MockitoBean
    CriteriaCommandService commandService;

    @Test
    @DisplayName("관리자 GET 은 명세 1.1 예시 형태(+ seniorDebtRatioLimit)로 200")
    void adminGetsGuarantee() throws Exception {
        when(queryService.getGuaranteeCriteria()).thenReturn(specExample());

        mockMvc.perform(get(GUARANTEE).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providers[0].provider").value("HUG"))
                .andExpect(jsonPath("$.data.providers[0].collateralRatio").value(90.0))
                .andExpect(jsonPath("$.data.providers[0].maxDeposit").value(700_000_000))
                .andExpect(jsonPath("$.data.providers[0].seniorDebtRatioLimit").value(60.0))
                .andExpect(jsonPath("$.data.providers[0].requiresLoanLink").value(false))
                .andExpect(jsonPath("$.data.providers[0].updatedAt").value("2026-07-01T00:00:00+09:00"))
                .andExpect(jsonPath("$.data.providers[0]", aMapWithSize(6)));
    }

    @Test
    @DisplayName("일반 사용자는 403 AUTH_FORBIDDEN")
    void userIsForbidden() throws Exception {
        mockMvc.perform(get(GUARANTEE).header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("토큰 없으면 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/criteria/history"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("관리자 PUT 은 토큰의 사용자 · 경로의 기관으로 수정하고 조회 결과를 돌려준다")
    void adminPutsGuarantee() throws Exception {
        when(queryService.getGuaranteeCriteria()).thenReturn(specExample());

        mockMvc.perform(put(GUARANTEE + "/HUG").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(SPEC_PUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providers[0].provider").value("HUG"));
        verify(commandService).updateGuarantee(eq(ADMIN_ID), eq(GuaranteeProvider.HUG), any());
    }

    @Test
    @DisplayName("사유가 없으면 400 INVALID_REQUEST")
    void missingReason() throws Exception {
        mockMvc.perform(put(GUARANTEE + "/HUG").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SPEC_PUT.replace("\"보증금 한도 예시 — 비수도권 5억 적용\"", "\"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("changeReason"));
        verify(commandService, never()).updateGuarantee(any(), any(), any());
    }

    @Test
    @DisplayName("없는 기관이면 400 INVALID_REQUEST")
    void unknownProvider() throws Exception {
        mockMvc.perform(put(GUARANTEE + "/KB").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(SPEC_PUT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("이력 size 가 100 을 넘으면 400")
    void historySizeOverMax() throws Exception {
        mockMvc.perform(get("/api/admin/criteria/history").param("size", "101")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("대출 규제 GET 은 명세 1.1 예시 형태(updatedAt 없음, 시행일은 날짜 문자열)로 200")
    void adminGetsLoanRegulations() throws Exception {
        when(queryService.getLoanRegulations()).thenReturn(loanRegulationExample());

        mockMvc.perform(get(LOAN_REGULATIONS).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].regulationId").value(1))
                .andExpect(jsonPath("$.data.items[0].houseType").value("ALL"))
                .andExpect(jsonPath("$.data.items[0].regionType").value("SEOUL_REGULATED"))
                .andExpect(jsonPath("$.data.items[0].depositRatioLimit").value(80.0))
                .andExpect(jsonPath("$.data.items[0].guaranteeCapNoHouse").value(400_000_000))
                .andExpect(jsonPath("$.data.items[0].guaranteeCapOneHouse").value(180_000_000))
                .andExpect(jsonPath("$.data.items[0].dsrLimit").value(40.0))
                .andExpect(jsonPath("$.data.items[0].stressDsrRate").value(3.0))
                .andExpect(jsonPath("$.data.items[0].dtiLimit").value(40.0))
                .andExpect(jsonPath("$.data.items[0].effectiveDate").value("2025-10-29"))
                .andExpect(jsonPath("$.data.items[0]", aMapWithSize(10)));
    }

    @Test
    @DisplayName("대출 규제 PUT 은 토큰의 사용자로 수정하고 조회 결과를 돌려준다")
    void adminPutsLoanRegulations() throws Exception {
        when(queryService.getLoanRegulations()).thenReturn(loanRegulationExample());

        mockMvc.perform(put(LOAN_REGULATIONS).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(LOAN_PUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].regulationId").value(1));
        verify(commandService).updateLoanRegulations(eq(ADMIN_ID), any());
    }

    @Test
    @DisplayName("대출 규제 비율이 100 이상이면 400 INVALID_REQUEST")
    void loanRegulationRatioOverMax() throws Exception {
        mockMvc.perform(put(LOAN_REGULATIONS).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOAN_PUT.replace("\"dsrLimit\": 40.00", "\"dsrLimit\": 100.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verify(commandService, never()).updateLoanRegulations(any(), any());
    }

    @Test
    @DisplayName("대출 규제 상한이 음수면 400 INVALID_REQUEST")
    void loanRegulationNegativeCap() throws Exception {
        mockMvc.perform(put(LOAN_REGULATIONS).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOAN_PUT.replace("\"guaranteeCapOneHouse\": 180000000",
                                "\"guaranteeCapOneHouse\": -1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verify(commandService, never()).updateLoanRegulations(any(), any());
    }

    @Test
    @DisplayName("대출 규제 목록이 비면 400 INVALID_REQUEST")
    void loanRegulationEmpty() throws Exception {
        mockMvc.perform(put(LOAN_REGULATIONS).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"regulations\": [], \"changeReason\": \"사유\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verify(commandService, never()).updateLoanRegulations(any(), any());
    }

    private static LoanRegulationsResponse loanRegulationExample() {
        return new LoanRegulationsResponse(List.of(new LoanRegulationsResponse.Item(1L, "ALL", "SEOUL_REGULATED",
                new BigDecimal("80.00"), 400_000_000L, 180_000_000L, new BigDecimal("40.00"),
                new BigDecimal("3.00"), new BigDecimal("40.00"), LocalDate.of(2025, 10, 29))));
    }

    private static String bearer(String role) {
        return "Bearer " + TOKENS.createAccessToken(ADMIN_ID, role);
    }

    private static GuaranteeCriteriaResponse specExample() {
        return new GuaranteeCriteriaResponse(List.of(new GuaranteeCriteriaResponse.Provider(
                GuaranteeProvider.HUG, new BigDecimal("90.00"), 700_000_000L, new BigDecimal("60.00"), false,
                OffsetDateTime.of(2026, 6, 30, 15, 0, 0, 0, ZoneOffset.UTC))));
    }

    @TestConfiguration
    static class TokenProviderTestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return TOKENS;
        }
    }
}
