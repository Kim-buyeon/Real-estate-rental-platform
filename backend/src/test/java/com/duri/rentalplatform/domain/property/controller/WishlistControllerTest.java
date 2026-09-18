package com.duri.rentalplatform.domain.property.controller;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.notification.store.StreamTicketStore;
import com.duri.rentalplatform.domain.property.dto.request.WishlistListRequest;
import com.duri.rentalplatform.domain.property.dto.response.WishlistResponse;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.service.WishlistCommandService;
import com.duri.rentalplatform.domain.property.service.WishlistQueryService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link WishlistController} 의 인증 「필수」 · 상태 코드 · 응답 형태 · 입력 검증. 서비스는 목킹하고
 * {@link SecurityConfig} 를 실제로 올린다.
 */
@WebMvcTest(controllers = WishlistController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WishlistControllerTest.TokenProviderTestConfig.class})
class WishlistControllerTest {

    private static final String SECRET = "wishlist-controller-test-secret-".repeat(3);
    private static final JwtTokenProvider TOKENS =
            new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
    private static final long USER_ID = 42L;
    private static final String PATH = "/api/me/wishlist";

    /** 실시간 수신 티켓을 소비하는 보안 체인의 의존. 이 슬라이스는 쓰지 않는다. */
    @MockitoBean
    StreamTicketStore streamTicketStore;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    WishlistQueryService queryService;

    @MockitoBean
    WishlistCommandService commandService;

    @Test
    @DisplayName("GET 은 명세 1.9 예시 형태로 200 이고 토큰의 사용자로 조회한다")
    void listMatchesSpec() throws Exception {
        when(queryService.findByUser(eq(USER_ID), any())).thenReturn(new CursorPage<>(List.of(
                new WishlistResponse(1024L, "강서구", 230_000_000L, RiskGrade.SAFE, RiskGrade.CAUTION,
                        OffsetDateTime.of(2026, 7, 25, 11, 20, 0, 0, ZoneOffset.ofHours(9)))), null, false));

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].propertyId").value(1024))
                .andExpect(jsonPath("$.data.items[0].district").value("강서구"))
                .andExpect(jsonPath("$.data.items[0].deposit").value(230_000_000))
                .andExpect(jsonPath("$.data.items[0].riskGrade").value("SAFE"))
                .andExpect(jsonPath("$.data.items[0].previousGrade").value("CAUTION"))
                .andExpect(jsonPath("$.data.items[0].addedAt").value("2026-07-25T11:20:00+09:00"))
                .andExpect(jsonPath("$.data.items[0]", aMapWithSize(6)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        verify(queryService).findByUser(USER_ID, new WishlistListRequest(null, null));
    }

    @Test
    @DisplayName("POST 는 201 이고 토큰의 사용자 · 본문의 매물로 등록한다")
    void addCreated() throws Exception {
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"propertyId\": 1024}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
        verify(commandService).add(USER_ID, 1024L);
    }

    @Test
    @DisplayName("POST 중복은 409 WISHLIST_DUPLICATED")
    void addDuplicated() throws Exception {
        doThrow(new BusinessException(ErrorCode.WISHLIST_DUPLICATED, "propertyId"))
                .when(commandService).add(USER_ID, 1024L);

        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"propertyId\": 1024}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WISHLIST_DUPLICATED"));
    }

    @Test
    @DisplayName("POST 에 propertyId 가 없으면 400 INVALID_REQUEST")
    void addMissingPropertyId() throws Exception {
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("propertyId"));
        verifyNoInteractions(commandService);
    }

    @Test
    @DisplayName("DELETE 는 204 이고 토큰의 사용자 · 경로의 매물로 해제한다")
    void removeNoContent() throws Exception {
        mockMvc.perform(delete(PATH + "/1024").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNoContent());
        verify(commandService).remove(USER_ID, 1024L);
    }

    @Test
    @DisplayName("GET size 가 100 을 넘으면 400")
    void listSizeOverMax() throws Exception {
        mockMvc.perform(get(PATH).param("size", "101").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("토큰 없으면 세 경로 모두 401")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"propertyId\": 1024}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(PATH + "/1024")).andExpect(status().isUnauthorized());
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
