package com.duri.rentalplatform.domain.property.dto.request;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 지도 묶음 요청. API 명세서(매물) 1.12 — 1.1 공통 검색 필터 + 표시 영역 네 값(모두 필수).
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
        @NotNull Double maxLng
) {

    /** 공통 검색 필터 부분. */
    public DistrictCountRequest toFilter() {
        return new DistrictCountRequest(district, contractType, depositMin, depositMax,
                monthlyRentMax, propertyType, riskGrade, areaMin, areaMax);
    }
}
