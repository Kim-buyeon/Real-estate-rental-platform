package com.duri.rentalplatform.domain.property.dto.request;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/**
 * 매물 조회 요청. API 명세서(매물) 1.1 공통 필터 + 1.3 좌표 조건 · 목록 파라미터.
 *
 * <p>좌표 조건(표시 영역 네 값 또는 중심 · 반경 세 값)이 있으면 마커, 없으면 목록이다. 판정은 서비스가 한다.
 *
 * <p>{@code size} 하한 · {@code radiusKm} 양수 제약은 명세에 없다. 0 이하가 SQL 오류(500)나 뒤집힌 범위로
 * 새지 않게 막는다 — 명세 보완 대상.
 */
public record PropertySearchRequest(
        String district,
        ContractType contractType,
        Long depositMin,
        Long depositMax,
        Long monthlyRentMax,
        PropertyType propertyType,
        List<RiskGrade> riskGrade,
        BigDecimal areaMin,
        BigDecimal areaMax,
        Double minLat,
        Double maxLat,
        Double minLng,
        Double maxLng,
        Double lat,
        Double lng,
        @Positive Double radiusKm,
        String sort,
        String cursor,
        @Min(1) @Max(PropertySearchRequest.MAX_SIZE) Integer size
) {

    /** API 명세서 공통 규약 1.4. */
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PropertySearchRequest {
        if (size == null) {
            size = DEFAULT_SIZE;
        }
    }

    /** 공통 검색 필터 부분. */
    public DistrictCountRequest toFilter() {
        return new DistrictCountRequest(district, contractType, depositMin, depositMax,
                monthlyRentMax, propertyType, riskGrade, areaMin, areaMax);
    }
}
