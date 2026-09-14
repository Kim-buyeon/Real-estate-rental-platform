package com.duri.rentalplatform.domain.notification.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import com.duri.rentalplatform.domain.notification.dto.request.NotificationSubscriptionUpdateRequest;
import com.duri.rentalplatform.domain.notification.dto.response.NotificationSubscriptionResponse;
import com.duri.rentalplatform.domain.notification.service.NotificationSubscriptionCommandService;
import com.duri.rentalplatform.domain.notification.service.NotificationSubscriptionQueryService;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
 * {@link NotificationSubscriptionController} 의 인증 「필수」 · 응답 형태 · 입력 검증. 서비스는 목킹하고
 * {@link SecurityConfig} 를 실제로 올린다.
 */
@WebMvcTest(controllers = NotificationSubscriptionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class,
        NotificationSubscriptionControllerTest.TokenProviderTestConfig.class})
class NotificationSubscriptionControllerTest {

    private static final String SECRET = "notification-subscription-test-secret-".repeat(3);
    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
    private static final long USER_ID = 42L;
    private static final String PATH = "/api/me/notification-subscriptions";

    /** 명세 1.2 예시 본문. */
    private static final String SPEC_BODY = """
            {
              "newProperty": {
                "enabled": true,
                "conditions": {
                  "districts": ["강서구", "구로구"],
                  "contractType": "DEPOSIT_ONLY",
                  "depositMax": 250000000
                }
              },
              "rateChange": { "enabled": true },
              "wishlistMonitoring": { "enabled": true },
              "consultSchedule": { "enabled": true }
            }
            """;

    private static final NotificationSubscriptionResponse SPEC_RESPONSE = new NotificationSubscriptionResponse(
            new NotificationSubscriptionResponse.NewProperty(true, new NotificationSubscriptionResponse.Conditions(
                    List.of("강서구", "구로구"), ContractType.DEPOSIT_ONLY, 250_000_000L)),
            new NotificationSubscriptionResponse.Toggle(true),
            new NotificationSubscriptionResponse.Toggle(true),
            new NotificationSubscriptionResponse.Toggle(true));

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationSubscriptionQueryService queryService;

    @MockitoBean
    NotificationSubscriptionCommandService commandService;

    @Test
    @DisplayName("GET 은 명세 1.2 예시 형태로 200 이고 토큰의 사용자로 조회한다")
    void getMatchesSpec() throws Exception {
        when(queryService.get(USER_ID)).thenReturn(SPEC_RESPONSE);

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", aMapWithSize(4)))
                .andExpect(jsonPath("$.data.newProperty.enabled").value(true))
                .andExpect(jsonPath("$.data.newProperty.conditions.districts[0]").value("강서구"))
                .andExpect(jsonPath("$.data.newProperty.conditions.districts[1]").value("구로구"))
                .andExpect(jsonPath("$.data.newProperty.conditions.contractType").value("DEPOSIT_ONLY"))
                .andExpect(jsonPath("$.data.newProperty.conditions.depositMax").value(250_000_000))
                .andExpect(jsonPath("$.data.rateChange.enabled").value(true))
                .andExpect(jsonPath("$.data.wishlistMonitoring.enabled").value(true))
                .andExpect(jsonPath("$.data.consultSchedule.enabled").value(true));
    }

    @Test
    @DisplayName("PUT 은 명세 예시 본문을 받아 수정한 뒤 조회 결과를 200 으로 돌려준다")
    void putReplacesThenReturnsGet() throws Exception {
        when(queryService.get(USER_ID)).thenReturn(SPEC_RESPONSE);

        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(SPEC_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.newProperty.conditions.districts[1]").value("구로구"));

        InOrder order = inOrder(commandService, queryService);
        order.verify(commandService).replace(USER_ID, new NotificationSubscriptionUpdateRequest(
                new NotificationSubscriptionUpdateRequest.NewProperty(true,
                        new NotificationSubscriptionUpdateRequest.Conditions(List.of("강서구", "구로구"),
                                ContractType.DEPOSIT_ONLY, 250_000_000L)),
                new NotificationSubscriptionUpdateRequest.Toggle(true),
                new NotificationSubscriptionUpdateRequest.Toggle(true),
                new NotificationSubscriptionUpdateRequest.Toggle(true)));
        order.verify(queryService).get(USER_ID);
    }

    @Test
    @DisplayName("PUT 에 항목이 빠지면 400 INVALID_REQUEST · field 는 그 항목")
    void putMissingSection() throws Exception {
        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SPEC_BODY.replace("\"consultSchedule\": { \"enabled\": true }",
                                "\"consultSchedule\": {}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("consultSchedule.enabled"));
        verifyNoInteractions(commandService, queryService);
    }

    @Test
    @DisplayName("PUT 에 열거에 없는 contractType 이면 400 INVALID_REQUEST · field 는 그 위치")
    void putUnknownContractType() throws Exception {
        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SPEC_BODY.replace("DEPOSIT_ONLY", "LEASE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("newProperty.conditions.contractType"));
        verifyNoInteractions(commandService, queryService);
    }

    @Test
    @DisplayName("PUT 서비스 검증 위반은 400 INVALID_REQUEST 와 서비스가 준 field")
    void putServiceValidation() throws Exception {
        doThrow(new BusinessException(ErrorCode.INVALID_REQUEST, "newProperty.conditions.districts"))
                .when(commandService).replace(eq(USER_ID), any());

        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(SPEC_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("newProperty.conditions.districts"));
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("토큰 없으면 GET · PUT 모두 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(SPEC_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(queryService, commandService);
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
