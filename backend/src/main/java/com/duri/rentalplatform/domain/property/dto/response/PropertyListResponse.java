package com.duri.rentalplatform.domain.property.dto.response;

import com.duri.rentalplatform.domain.property.enums.ContractType;
import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 매물 목록 한 건. API 명세서(매물) 1.6.
 *
 * <p>{@code registeredAt} 은 Asia/Seoul 오프셋으로 내보낸다(공통 규약 1.1). 드라이버가 돌려주는
 * 오프셋은 UTC 라 생성자에서 같은 시각의 서울 오프셋으로 맞춘다.
 */
public record PropertyListResponse(
        Long propertyId,
        String district,
        String address,
        PropertyType propertyType,
        ContractType contractType,
        Long deposit,
        Long monthlyRent,
        BigDecimal areaSqm,
        Integer floor,
        RiskGrade riskGrade,
        BigDecimal debtRatio,
        OffsetDateTime registeredAt
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public PropertyListResponse {
        if (registeredAt != null) {
            registeredAt = registeredAt.atZoneSameInstant(SEOUL).toOffsetDateTime();
        }
    }
}
