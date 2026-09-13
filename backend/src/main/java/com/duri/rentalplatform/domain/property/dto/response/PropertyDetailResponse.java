package com.duri.rentalplatform.domain.property.dto.response;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PriceType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.property.vo.PropertyDetailRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 매물 상세. API 명세서(매물) 1.7.
 *
 * <p>{@code riskSummary} 는 분석 이력이 없으면 null 이다.
 */
public record PropertyDetailResponse(
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
        RiskSummary riskSummary,
        boolean wishlisted,
        OffsetDateTime registeredAt
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 최신 분석 결과 요약. */
    public record RiskSummary(RiskGrade riskGrade, BigDecimal debtRatio, Boolean insuranceEligible) {
    }

    /** 매퍼 행을 응답으로 옮긴다. 위험 등급이 없으면 분석 이력이 없는 매물이다(risk_grade 는 NOT NULL). */
    public static PropertyDetailResponse from(PropertyDetailRow row) {
        RiskSummary riskSummary = row.riskGrade() == null
                ? null
                : new RiskSummary(row.riskGrade(), row.debtRatio(), row.insuranceEligible());
        OffsetDateTime registeredAt = row.registeredAt() == null
                ? null
                : row.registeredAt().atZoneSameInstant(SEOUL).toOffsetDateTime();
        return new PropertyDetailResponse(
                row.propertyId(), row.district(), row.address(), row.latitude(), row.longitude(),
                row.propertyType(), row.contractType(), row.deposit(), row.monthlyRent(),
                row.areaSqm(), row.floor(), row.landlordName(), row.marketPrice(), row.priceType(),
                row.priceDate(), riskSummary, Boolean.TRUE.equals(row.wishlisted()), registeredAt);
    }
}
