package com.duri.rentalplatform.domain.property.dto.request;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import java.math.BigDecimal;
import java.util.List;

/**
 * 자치구 집계 요청. API 명세서(매물) 1.1 공통 검색 필터만 받는다.
 *
 * <p>{@code propertyType} 은 적재된 코드값만 받는다 — {@link PropertyType} 에 없는 값은 바인딩에서 400.
 */
public record DistrictCountRequest(
        String district,
        ContractType contractType,
        Long depositMin,
        Long depositMax,
        Long monthlyRentMax,
        PropertyType propertyType,
        List<RiskGrade> riskGrade,
        BigDecimal areaMin,
        BigDecimal areaMax
) {
}
