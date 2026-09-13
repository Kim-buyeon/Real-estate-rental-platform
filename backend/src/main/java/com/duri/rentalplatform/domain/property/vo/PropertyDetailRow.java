package com.duri.rentalplatform.domain.property.vo;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 매물 상세 매퍼 행. 응답의 {@code riskSummary} 는 미분석이면 객체 자체가 null 이어야 해서 평면으로 받고
 * 응답의 정적 팩토리가 묶는다.
 */
public record PropertyDetailRow(
        Long propertyId,
        String district,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        PropertyType propertyType,
        ContractType contractType,
        Long deposit,
        Long monthlyRent,
        BigDecimal areaSqm,
        Integer floor,
        String landlordName,
        Long marketPrice,
        PriceType priceType,
        LocalDate priceDate,
        RiskGrade riskGrade,
        BigDecimal debtRatio,
        Boolean insuranceEligible,
        Boolean wishlisted,
        OffsetDateTime registeredAt
) {
}
