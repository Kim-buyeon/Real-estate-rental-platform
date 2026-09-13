package com.duri.rentalplatform.domain.property.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 건축물대장 응답의 매퍼 행. 주거용 여부는 저장하지 않으므로 응답의 정적 팩토리가 주용도에서 만든다.
 *
 * @param collectedAt 드라이버 오프셋 그대로다. 서울 오프셋 맞춤은 응답이 한다
 */
public record LedgerRow(
        OffsetDateTime collectedAt,
        LocalDate approvalDate,
        BigDecimal exclusiveArea,
        BigDecimal totalFloorArea,
        boolean violationBuilding,
        String mainPurpose,
        Long propertyId
) {
}
