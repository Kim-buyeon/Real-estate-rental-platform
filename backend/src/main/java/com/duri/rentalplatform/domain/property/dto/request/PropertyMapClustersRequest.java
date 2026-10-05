package com.duri.rentalplatform.domain.property.dto.request;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 지도 묶음 요청. API 명세서(매물) 1.12 — 1.1 공통 검색 필터 + 표시 영역 네 값(모두 필수) + 격자 행 · 열 수(선택).
 *
 * <p>{@code rows} · {@code cols} 는 1 ~ 24 이고 없으면(null) 기본 12 로 채운다. 범위 밖 · 정수 아님은 바인딩 · 검증에서
 * INVALID_REQUEST(400) 로 그 이름을 {@code field} 에 담는다.
 *
 * <p>반경 조건 · 정렬 · 커서는 받지 않는다. {@code min > max} 는 두 값을 함께 봐야 해 서비스가 거부한다.
 */
public record PropertyMapClustersRequest(
        String district,
        ContractType contractType,
        Long depositMin,
        Long depositMax,
        Long monthlyRentMax,
        PropertyType propertyType,
        List<RiskGrade> riskGrade,
        BigDecimal areaMin,
        BigDecimal areaMax,
        @NotNull Double minLat,
        @NotNull Double maxLat,
        @NotNull Double minLng,
        @NotNull Double maxLng,
        @Min(1) @Max(PropertyMapClustersRequest.MAX_GRID_DIVISIONS) Integer rows,
        @Min(1) @Max(PropertyMapClustersRequest.MAX_GRID_DIVISIONS) Integer cols
) {

    /** 격자의 기본 행 · 열 수 — 요청에 {@code rows} · {@code cols} 가 없으면 이 수만큼 나눈다. API 명세서(매물) 1.12. */
    public static final int DEFAULT_GRID_DIVISIONS = 12;

    /**
     * 격자 행 · 열 수 상한. API 명세서(매물) 1.12 — 응답이 많아야 1 + 576 항목(묶음 JSON 칸당 약 220 B → 약 127 KB,
     * 2026-10-05 운영 실측).
     */
    public static final int MAX_GRID_DIVISIONS = 24;

    public PropertyMapClustersRequest {
        if (rows == null) {
            rows = DEFAULT_GRID_DIVISIONS;
        }
        if (cols == null) {
            cols = DEFAULT_GRID_DIVISIONS;
        }
    }

    /** 공통 검색 필터 부분. */
    public DistrictCountRequest toFilter() {
        return new DistrictCountRequest(district, contractType, depositMin, depositMax,
                monthlyRentMax, propertyType, riskGrade, areaMin, areaMax);
    }
}
