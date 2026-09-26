package com.duri.rentalplatform.domain.property.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.CursorPage;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.common.GlobalExceptionHandler;
import com.duri.rentalplatform.common.security.JwtTokenProvider;
import com.duri.rentalplatform.common.security.StreamTicketStore;
import com.duri.rentalplatform.config.SecurityConfig;
import com.duri.rentalplatform.domain.property.dto.request.PropertyMapClustersRequest;
import com.duri.rentalplatform.domain.property.dto.request.PropertySearchRequest;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMapClustersResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMarkerResponse;
import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.service.PropertyQueryService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link PropertyController} 의 인가 · 요청 검증을 확인한다. 서비스는 목킹한다 — 여기서 보는 것은
 * 라우팅 · 인가 · 바인딩이지 조회 로직이 아니다(그건 {@code PropertyQueryServiceTest}).
 *
 * <p>{@link SecurityConfig} 를 실제로 올려 GET {@code /api/properties/**} 가 인증 「선택」(permitAll)
 * 이라는 실제 필터체인 규칙을 검증한다. {@code SecurityAccessDeniedTest} 와 같은 방식으로
 * {@link JwtTokenProvider} 만 시험용 빈으로 대체한다.
 */
@WebMvcTest(controllers = PropertyController.class)
@Import({
    SecurityConfig.class,
    GlobalExceptionHandler.class,
    PropertyControllerTest.TokenProviderTestConfig.class
})
class PropertyControllerTest {

    private static final String SECRET = "property-controller-test-secret-".repeat(3);

    /** 실시간 수신 티켓을 소비하는 보안 체인의 의존. 이 슬라이스는 쓰지 않는다. */
    @MockitoBean
    StreamTicketStore streamTicketStore;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PropertyQueryService queryService;

    @Test
    @DisplayName("토큰 없이 목록을 조회해도 401이 아니다 — 인증 선택 경로다")
    void searchListIsPublic() throws Exception {
        when(queryService.search(any(PropertySearchRequest.class)))
                .thenReturn(new CursorPage<>(java.util.List.of(), null, false));

        mockMvc.perform(get("/api/properties"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("토큰 없이 자치구 집계를 조회해도 401이 아니다 — 인증 선택 경로다")
    void districtCountsIsPublic() throws Exception {
        mockMvc.perform(get("/api/properties/district-counts"))
                .andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }

    @Test
    @DisplayName("토큰 없이 상세를 조회해도 401이 아니다 — 인증 선택 경로다")
    void detailIsPublic() throws Exception {
        mockMvc.perform(get("/api/properties/1"))
                .andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }

    @Test
    @DisplayName("잘못된 propertyType 값은 바인딩에서 400 INVALID_REQUEST다")
    void invalidPropertyTypeReturns400() throws Exception {
        mockMvc.perform(get("/api/properties").param("propertyType", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("propertyId가 숫자가 아니면 400 INVALID_REQUEST다")
    void nonNumericPropertyIdReturns400() throws Exception {
        mockMvc.perform(get("/api/properties/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("잘못된 정렬 값은 서비스가 던진 INVALID_REQUEST를 그대로 400으로 전달한다")
    void invalidSortReturns400() throws Exception {
        when(queryService.search(any(PropertySearchRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_REQUEST, "sort"));

        mockMvc.perform(get("/api/properties").param("sort", "not-a-real-sort"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("sort"));
    }

    @Test
    @DisplayName("size가 최대치(100)를 넘으면 400 INVALID_REQUEST다")
    void sizeOverMaxReturns400() throws Exception {
        mockMvc.perform(get("/api/properties").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // ---------- 지도 묶음 (명세 1.12) ----------

    @Test
    @DisplayName("지도 묶음: 표시 영역 값이 하나라도 없으면 400 INVALID_REQUEST 이고 서비스를 부르지 않는다")
    void mapClustersMissingBoxReturns400() throws Exception {
        mockMvc.perform(get("/api/properties/map-clusters")
                        .param("district", "강서구")
                        .param("minLat", "37.52").param("maxLat", "37.58").param("minLng", "126.81"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.field").value("maxLng"));

        verify(queryService, never()).getMapClusters(any());
    }

    @Test
    @DisplayName("지도 묶음: 토큰 없이 호출되고 응답 JSON 키가 명세 1.12 예시와 같다(gradeCounts 는 대문자 키)")
    void mapClustersResponseShape() throws Exception {
        PropertyMapClustersResponse.Cluster cluster = new PropertyMapClustersResponse.Cluster("5:7",
                new BigDecimal("37.5476"), new BigDecimal("126.8601"), 214,
                new PropertyMapClustersResponse.GradeCounts(80, 71, 58, 5),
                new BigDecimal("37.545"), new BigDecimal("37.55"),
                new BigDecimal("126.8567"), new BigDecimal("126.8633"));
        PropertyMarkerResponse marker = new PropertyMarkerResponse(41408L, new BigDecimal("37.5791"),
                new BigDecimal("126.8104"), 150_000_000L, RiskGrade.CAUTION, ContractType.DEPOSIT_ONLY, 0L,
                "강서구", new BigDecimal("74.5"), false);
        when(queryService.getMapClusters(any(PropertyMapClustersRequest.class)))
                .thenReturn(PropertyMapClustersResponse.clustered(6003L, List.of(cluster), List.of(marker)));

        mockMvc.perform(get("/api/properties/map-clusters")
                        .param("district", "강서구")
                        .param("minLat", "37.52").param("maxLat", "37.58")
                        .param("minLng", "126.81").param("maxLng", "126.89"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(6003))
                .andExpect(jsonPath("$.data.clustered").value(true))
                .andExpect(jsonPath("$.data.clusters[0].key").value("5:7"))
                .andExpect(jsonPath("$.data.clusters[0].latitude").value(37.5476))
                .andExpect(jsonPath("$.data.clusters[0].longitude").value(126.8601))
                .andExpect(jsonPath("$.data.clusters[0].count").value(214))
                .andExpect(jsonPath("$.data.clusters[0].gradeCounts.SAFE").value(80))
                .andExpect(jsonPath("$.data.clusters[0].gradeCounts.CAUTION").value(71))
                .andExpect(jsonPath("$.data.clusters[0].gradeCounts.DANGER").value(58))
                .andExpect(jsonPath("$.data.clusters[0].gradeCounts.UNANALYZED").value(5))
                .andExpect(jsonPath("$.data.clusters[0].gradeCounts.safe").doesNotExist())
                .andExpect(jsonPath("$.data.clusters[0].minLat").value(37.545))
                .andExpect(jsonPath("$.data.clusters[0].maxLat").value(37.55))
                .andExpect(jsonPath("$.data.clusters[0].minLng").value(126.8567))
                .andExpect(jsonPath("$.data.clusters[0].maxLng").value(126.8633))
                .andExpect(jsonPath("$.data.markers[0].propertyId").value(41408))
                .andExpect(jsonPath("$.data.markers[0].riskGrade").value("CAUTION"))
                .andExpect(jsonPath("$.data.markers[0].hasSeniorDebt").value(false));
    }

    @TestConfiguration
    static class TokenProviderTestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));
        }
    }
}
