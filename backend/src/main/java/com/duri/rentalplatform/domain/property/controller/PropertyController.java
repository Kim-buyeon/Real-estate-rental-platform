package com.duri.rentalplatform.domain.property.controller;

import com.duri.rentalplatform.common.ApiResponse;
import com.duri.rentalplatform.domain.property.dto.request.DistrictCountRequest;
import com.duri.rentalplatform.domain.property.dto.request.PropertyMapClustersRequest;
import com.duri.rentalplatform.domain.property.dto.request.PropertySearchRequest;
import com.duri.rentalplatform.domain.property.dto.response.DistrictCountsResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyDetailResponse;
import com.duri.rentalplatform.domain.property.dto.response.PropertyMapClustersResponse;
import com.duri.rentalplatform.domain.property.service.PropertyQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매물 조회. API 명세서(매물) 1장 PROP-01 · 02 · 03 · 08. 모두 인증 「선택」이다 — 토큰이 없으면
 * {@code userId} 가 null 이다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyQueryService queryService;

    @GetMapping("/district-counts")
    public ApiResponse<DistrictCountsResponse> districtCounts(
            @Valid @ModelAttribute DistrictCountRequest request) {
        return ApiResponse.ok(queryService.getDistrictCounts(request));
    }

    /**
     * 지도 묶음. 명세 1.12. 고정 경로라 {@code /{propertyId}} 보다 먼저 매칭된다. 표시 영역 누락은 바인딩 검증이,
     * {@code min > max} 는 서비스가 INVALID_REQUEST 로 거부한다.
     */
    @GetMapping("/map-clusters")
    public ApiResponse<PropertyMapClustersResponse> mapClusters(
            @Valid @ModelAttribute PropertyMapClustersRequest request) {
        return ApiResponse.ok(queryService.getMapClusters(request));
    }

    /** 좌표 조건이 있으면 마커({@code items} + {@code count}), 없으면 커서 목록. 명세 1.3. */
    @GetMapping
    public ApiResponse<Object> search(@Valid @ModelAttribute PropertySearchRequest request) {
        return ApiResponse.ok(queryService.search(request));
    }

    @GetMapping("/{propertyId}")
    public ApiResponse<PropertyDetailResponse> detail(
            @PathVariable Long propertyId,
            @AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(queryService.getDetail(propertyId, userId));
    }
}
