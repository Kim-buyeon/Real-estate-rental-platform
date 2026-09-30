package com.duri.rentalplatform.domain.property.vo;

import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 건축물대장 응답의 매퍼 행. 주거용 여부는 저장하지 않으므로 응답의 정적 팩토리가 주용도에서 만든다.
 *
 * <p>매물 기준으로 대장을 붙여 읽는다. 매물은 있는데 대장이 없으면 {@code propertyId} 만 차고 나머지는 null 이다.
 *
 * @param collectedAt       드라이버 오프셋 그대로다. 서울 오프셋 맞춤은 응답이 한다. 대장이 없으면 null
 * @param violationBuilding 위반건축물 표기. null 은 「확인하지 못함」(V17)이라 원시 타입으로 받지 않는다 — 원시 타입이면 null
 *                          행에서 매핑이 실패한다
 * @param dataSource        대장 수집 출처. 대장이 없으면 null
 */
public record LedgerRow(
        OffsetDateTime collectedAt,
        LocalDate approvalDate,
        BigDecimal exclusiveArea,
        BigDecimal totalFloorArea,
        Boolean violationBuilding,
        String mainPurpose,
        Long propertyId,
        LedgerDataSource dataSource
) {
}
