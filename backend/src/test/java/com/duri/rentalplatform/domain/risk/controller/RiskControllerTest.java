package com.duri.rentalplatform.domain.risk.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.dto.response.RiskResponse;
import com.duri.rentalplatform.domain.risk.enums.GradeReason;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeFailedCondition;
import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.PersonalCondition;
import com.duri.rentalplatform.domain.risk.service.RiskAnalysisCommandService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link RiskController} 의 인가 · 응답 형태 · 오류 경로. 서비스는 목킹한다.
 *
 * <p>응답 형태는 API 명세서(위험도 분석) 1.1 예시와 필드 이름 · 중첩 · 형식을 대조한다. {@link SecurityConfig} 를 실제로
 * 올려 인증 「선택」 경로가 토큰 없이 열리는지 본다.
 */
@WebMvcTest(controllers = RiskController.class)
@Import({
    SecurityConfig.class,
    GlobalExceptionHandler.class,
    RiskControllerTest.TokenProviderTestConfig.class
})
class RiskControllerTest {

    private static final String SECRET = "risk-controller-test-secret-".repeat(3);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RiskAnalysisCommandService commandService;

    @Test
    @DisplayName("토큰 없이 조회하면 명세 1.1 예시와 같은 형태로 응답한다")
    void responseMatchesSpecExample() throws Exception {
        when(commandService.analyze(1024L)).thenReturn(specExample());

        mockMvc.perform(get("/api/properties/1024/risk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.riskGrade").value("DANGER"))
                .andExpect(jsonPath("$.data.gradeReason").value("NEGATIVE_EQUITY"))
                .andExpect(jsonPath("$.data.debtRatio").value(116.7))
                .andExpect(jsonPath("$.data.marketPrice").value(300_000_000))
                .andExpect(jsonPath("$.data.priceType").value("ACTUAL_TRANSACTION"))
                .andExpect(jsonPath("$.data.priceDate").value("2026-06-30"))
                .andExpect(jsonPath("$.data.seniorDebtTotal").value(250_000_000))
                .andExpect(jsonPath("$.data.isNegativeEquity").value(true))
                .andExpect(jsonPath("$.data.insuranceEligible").value(false))
                .andExpect(jsonPath("$.data.providers[*].provider").value(contains("HUG", "HF", "SGI")))
                .andExpect(jsonPath("$.data.providers[0].eligible").value(false))
                .andExpect(jsonPath("$.data.providers[0].failedConditions")
                        .value(contains("DEBT_RATIO_EXCEEDED", "SENIOR_DEBT_RATIO_EXCEEDED")))
                .andExpect(jsonPath("$.data.providers[0].loanLinkRequired").value(false))
                .andExpect(jsonPath("$.data.providers[1].loanLinkRequired").value(true))
                .andExpect(jsonPath("$.data.providers[0].guaranteeLimit").value(20_000_000))
                .andExpect(jsonPath("$.data.providers[0].estimatedPremium").value(nullValue()))
                .andExpect(jsonPath("$.data.providers[0].productName").value(nullValue()))
                .andExpect(jsonPath("$.data.providers[0]", aMapWithSize(7)))
                .andExpect(jsonPath("$.data.personalConditions").value(contains("ANNUAL_INCOME",
                        "APPLICATION_DEADLINE", "NEW_OR_RENEWAL", "RESIDENTIAL_USE_NOTATION", "BROKER_CONTRACT",
                        "MOVE_IN_AND_FIXED_DATE")))
                .andExpect(jsonPath("$.data.rightViolations").value(empty()))
                .andExpect(jsonPath("$.data.warnings").value(contains("PROVISIONAL_REGISTRATION")))
                .andExpect(jsonPath("$.data.consistency.ownerNameMatched").value(true))
                .andExpect(jsonPath("$.data.consistency.addressMatched").value(true))
                .andExpect(jsonPath("$.data.consistency.violationBuilding").value(false))
                .andExpect(jsonPath("$.data.consistency.areaMatched").value(true))
                .andExpect(jsonPath("$.data.consistency", aMapWithSize(4)))
                .andExpect(jsonPath("$.data.analyzedAt").value("2026-07-29T03:00:00+09:00"))
                .andExpect(jsonPath("$.data", aMapWithSize(15)));
    }

    @Test
    @DisplayName("매물이 없으면 404 PROPERTY_NOT_FOUND")
    void propertyNotFound() throws Exception {
        when(commandService.analyze(99L)).thenThrow(new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));

        mockMvc.perform(get("/api/properties/99/risk"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PROPERTY_NOT_FOUND"));
    }

    @Test
    @DisplayName("등기 · 대장 수집이 실패하면 503 EXTERNAL_API_UNAVAILABLE")
    void externalUnavailable() throws Exception {
        when(commandService.analyze(7L)).thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_UNAVAILABLE));

        mockMvc.perform(get("/api/properties/7/risk"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EXTERNAL_API_UNAVAILABLE"));
    }

    @Test
    @DisplayName("propertyId 가 숫자가 아니면 400 INVALID_REQUEST")
    void nonNumericPropertyId() throws Exception {
        mockMvc.perform(get("/api/properties/abc/risk"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private static RiskResponse specExample() {
        return new RiskResponse(
                RiskGrade.DANGER,
                GradeReason.NEGATIVE_EQUITY,
                new BigDecimal("116.70"),
                300_000_000L,
                PriceType.ACTUAL_TRANSACTION,
                LocalDate.of(2026, 6, 30),
                250_000_000L,
                true,
                false,
                List.of(
                        new RiskResponse.Provider(GuaranteeProvider.HUG, false,
                                List.of(GuaranteeFailedCondition.DEBT_RATIO_EXCEEDED,
                                        GuaranteeFailedCondition.SENIOR_DEBT_RATIO_EXCEEDED),
                                false, 20_000_000L, null, null),
                        new RiskResponse.Provider(GuaranteeProvider.HF, false,
                                List.of(GuaranteeFailedCondition.DEBT_RATIO_EXCEEDED), true, 20_000_000L, null, null),
                        new RiskResponse.Provider(GuaranteeProvider.SGI, false,
                                List.of(GuaranteeFailedCondition.DEBT_RATIO_EXCEEDED), false, 20_000_000L, null,
                                null)),
                List.of(PersonalCondition.values()),
                List.of(),
                List.of(OwnershipRightType.PROVISIONAL_REGISTRATION),
                new RiskResponse.Consistency(true, true, false, true),
                OffsetDateTime.of(2026, 7, 29, 3, 0, 0, 0, ZoneOffset.ofHours(9)));
    }

    @TestConfiguration
    static class TokenProviderTestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
        }
    }
}
