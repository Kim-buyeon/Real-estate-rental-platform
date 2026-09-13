package com.duri.rentalplatform.domain.property.dto.response;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import java.math.BigDecimal;

/**
 * 지도 마커 한 건. API 명세서(매물) 1.4.
 *
 * <p>미분석 매물은 {@code riskGrade} · {@code debtRatio} · {@code hasSeniorDebt} 가 null 이다.
 */
public record PropertyMarkerResponse(
        Long propertyId,
        BigDecimal latitude,
        BigDecimal longitude,
        Long deposit,
        RiskGrade riskGrade,
        ContractType contractType,
        Long monthlyRent,
        String district,
        BigDecimal debtRatio,
        Boolean hasSeniorDebt
) {
}
