package com.duri.rentalplatform.domain.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.empty;
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

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.user.dto.request.ProfileUpdateRequest;
import com.duri.rentalplatform.domain.user.dto.response.ProfileResponse;
import com.duri.rentalplatform.domain.user.enums.Role;
import com.duri.rentalplatform.domain.user.service.UserCommandService;
import com.duri.rentalplatform.domain.user.service.UserQueryService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * {@link ProfileController} 의 인증 · 응답 형태 · 검증. 서비스는 목킹한다.
 *
 * <p>응답 형태는 API 명세서(회원 · 인증) 1.1 예시와 필드 이름 · 중첩 · 형식을 대조한다. {@link SecurityConfig} 를 실제로
 * 올려 인증 「필수」 경로가 토큰 없이 막히는지 본다.
 */
@WebMvcTest(controllers = ProfileController.class)
@Import({
    SecurityConfig.class,
    GlobalExceptionHandler.class,
    ProfileControllerTest.TokenProviderTestConfig.class
})
class ProfileControllerTest {

    private static final String SECRET = "profile-controller-test-secret-".repeat(3);
    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
    private static final long USER_ID = 42L;
    private static final String PATH = "/api/me/profile";

    private static final String SPEC_REQUEST = """
            {
              "account": { "name": "홍길동", "phone": "010-1234-5678" },
              "profile": {
                "annualIncome": 45000000, "creditScore": 820, "existingLoan": 0,
                "existingLoanAnnualPayment": 0, "hasHouse": false, "ownFund": 50000000
              }
            }
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserQueryService queryService;

    @MockitoBean
    UserCommandService commandService;

    @Test
    @DisplayName("GET 응답이 명세 1.1 예시와 같은 형태다")
    void getMatchesSpecExample() throws Exception {
        when(queryService.getProfile(USER_ID)).thenReturn(specExample());

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.account.name").value("홍길동"))
                .andExpect(jsonPath("$.data.account.phone").value("010-1234-5678"))
                .andExpect(jsonPath("$.data.account.email").value("user@example.com"))
                .andExpect(jsonPath("$.data.account.role").value("USER"))
                .andExpect(jsonPath("$.data.account.createdAt").value("2026-07-01T09:12:00+09:00"))
                .andExpect(jsonPath("$.data.account", aMapWithSize(5)))
                .andExpect(jsonPath("$.data.profile.annualIncome").value(42_000_000))
                .andExpect(jsonPath("$.data.profile.creditScore").value(820))
                .andExpect(jsonPath("$.data.profile.existingLoan").value(0))
                .andExpect(jsonPath("$.data.profile.existingLoanAnnualPayment").value(0))
                .andExpect(jsonPath("$.data.profile.hasHouse").value(false))
                .andExpect(jsonPath("$.data.profile.ownFund").value(50_000_000))
                .andExpect(jsonPath("$.data.profile", aMapWithSize(6)))
                .andExpect(jsonPath("$.data.missingFields").value(empty()))
                .andExpect(jsonPath("$.data", aMapWithSize(3)));
    }

    @Test
    @DisplayName("토큰 없이 조회하면 401 AUTH_INVALID_CREDENTIAL")
    void getWithoutToken() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("PUT 은 토큰의 사용자로 수정한 뒤 조회 결과를 200 으로 돌려주고, 수정 불가 필드는 무시한다")
    void putUpdatesAndReturnsProfile() throws Exception {
        when(queryService.getProfile(USER_ID)).thenReturn(specExample());
        String withReadOnly = SPEC_REQUEST.replace("\"phone\": \"010-1234-5678\"",
                "\"phone\": \"010-1234-5678\", \"email\": \"x@example.com\", \"role\": \"ADMIN\"");

        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(withReadOnly))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.name").value("홍길동"));

        ArgumentCaptor<ProfileUpdateRequest> captor = ArgumentCaptor.forClass(ProfileUpdateRequest.class);
        verify(commandService).updateProfile(eq(USER_ID), captor.capture());
        assertRequest(captor.getValue());
    }

    @Test
    @DisplayName("이름이 비면 400 INVALID_REQUEST 이고 수정하지 않는다")
    void blankNameIsInvalid() throws Exception {
        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SPEC_REQUEST.replace("\"name\": \"홍길동\"", "\"name\": \"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verify(commandService, never()).updateProfile(any(), any());
    }

    @Test
    @DisplayName("금액이 음수면 400 INVALID_REQUEST")
    void negativeAmountIsInvalid() throws Exception {
        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SPEC_REQUEST.replace("\"ownFund\": 50000000", "\"ownFund\": -1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("신용점수가 1000 을 넘으면 400 INVALID_REQUEST")
    void creditScoreOverMaxIsInvalid() throws Exception {
        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SPEC_REQUEST.replace("\"creditScore\": 820", "\"creditScore\": 1001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("profile 묶음이 없으면 400 INVALID_REQUEST")
    void missingProfileIsInvalid() throws Exception {
        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\": {\"name\": \"홍길동\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("토큰 없이 수정하면 401")
    void putWithoutToken() throws Exception {
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(SPEC_REQUEST))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(commandService);
    }

    @Test
    @DisplayName("탈퇴한 사용자면 401 AUTH_INVALID_CREDENTIAL")
    void deletedUser() throws Exception {
        when(queryService.getProfile(USER_ID)).thenThrow(new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIAL));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIAL"));
    }

    private static void assertRequest(ProfileUpdateRequest request) {
        assertThat(request.account())
                .isEqualTo(new ProfileUpdateRequest.Account("홍길동", "010-1234-5678"));
        assertThat(request.profile())
                .isEqualTo(new ProfileUpdateRequest.Profile(45_000_000L, 820, 0L, 0L, false, 50_000_000L));
    }

    private static String bearer() {
        return "Bearer " + TOKENS.createAccessToken(USER_ID, "USER");
    }

    private static ProfileResponse specExample() {
        return new ProfileResponse(
                new ProfileResponse.Account("홍길동", "010-1234-5678", "user@example.com", Role.USER,
                        OffsetDateTime.of(2026, 7, 1, 9, 12, 0, 0, ZoneOffset.ofHours(9))),
                new ProfileResponse.Profile(42_000_000L, 820, 0L, 0L, false, 50_000_000L),
                List.of());
    }

    @TestConfiguration
    static class TokenProviderTestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return TOKENS;
        }
    }
}
