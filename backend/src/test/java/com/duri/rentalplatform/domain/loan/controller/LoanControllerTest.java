package com.duri.rentalplatform.domain.loan.controller;

import static org.hamcrest.Matchers.empty;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.loan.dto.response.LoanLimitResponse;
import com.duri.rentalplatform.domain.loan.enums.AppliedRegulation;
import com.duri.rentalplatform.domain.loan.service.LoanCommandService;
import java.math.BigDecimal;
import java.time.Duration;
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
 * {@link LoanController} — 명세(대출) 1.1 응답 필드 이름 · 인증 「필수」 · 오류 봉투. 서비스는 목킹한다.
 */
@WebMvcTest(controllers = LoanController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, LoanControllerTest.TokenProviderTestConfig.class})
class LoanControllerTest {

    private static final String SECRET = "loan-controller-test-secret-".repeat(3);
    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
    private static final long USER_ID = 42L;
    private static final long PROPERTY_ID = 1024L;
    private static final String PATH = "/api/loans/limit";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LoanCommandService commandService;

    @Test
    @DisplayName("응답이 명세 1.1 예시와 같은 필드 이름 · 형태다")
    void matchesSpecExample() throws Exception {
        when(commandService.calculateLimit(USER_ID, PROPERTY_ID)).thenReturn(new LoanLimitResponse(160_000_000L,
                180_000_000L, 95_238_095L, 55_555_555L, 95_238_095L, AppliedRegulation.DSR, new BigDecimal("40.0"),
                List.of()));

        mockMvc.perform(get(PATH).param("propertyId", String.valueOf(PROPERTY_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.depositLimit").value(160_000_000))
                .andExpect(jsonPath("$.data.guaranteeCapLimit").value(180_000_000))
                .andExpect(jsonPath("$.data.dsrLimit").value(95_238_095))
                .andExpect(jsonPath("$.data.stressDsrLimit").value(55_555_555))
                .andExpect(jsonPath("$.data.finalLimit").value(95_238_095))
                .andExpect(jsonPath("$.data.appliedRegulation").value("DSR"))
                .andExpect(jsonPath("$.data.dtiReference").value(40.0))
                .andExpect(jsonPath("$.data.missingFields", empty()));
    }

    @Test
    @DisplayName("무주택이면 dsrLimit · stressDsrLimit 이 null 로 나간다")
    void noHouseNullLimits() throws Exception {
        when(commandService.calculateLimit(USER_ID, PROPERTY_ID)).thenReturn(new LoanLimitResponse(240_000_000L,
                400_000_000L, null, null, 240_000_000L, AppliedRegulation.DEPOSIT_RATIO, new BigDecimal("20.2"),
                List.of()));

        mockMvc.perform(get(PATH).param("propertyId", String.valueOf(PROPERTY_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dsrLimit").isEmpty())
                .andExpect(jsonPath("$.data.stressDsrLimit").isEmpty())
                .andExpect(jsonPath("$.data.appliedRegulation").value("DEPOSIT_RATIO"));
    }

    @Test
    @DisplayName("토큰이 없으면 401")
    void unauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get(PATH).param("propertyId", String.valueOf(PROPERTY_ID)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(commandService);
    }

    @Test
    @DisplayName("PROFILE_INCOMPLETE 는 422 와 field annualIncome")
    void profileIncomplete() throws Exception {
        when(commandService.calculateLimit(USER_ID, PROPERTY_ID))
                .thenThrow(new BusinessException(ErrorCode.PROFILE_INCOMPLETE, "annualIncome"));

        mockMvc.perform(get(PATH).param("propertyId", String.valueOf(PROPERTY_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PROFILE_INCOMPLETE"))
                .andExpect(jsonPath("$.error.field").value("annualIncome"));
    }

    @Test
    @DisplayName("propertyId 가 없으면 400 INVALID_REQUEST")
    void missingPropertyId() throws Exception {
        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("propertyId"));
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
