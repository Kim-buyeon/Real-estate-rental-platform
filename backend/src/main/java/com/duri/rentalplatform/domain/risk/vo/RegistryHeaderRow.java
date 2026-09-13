package com.duri.rentalplatform.domain.risk.vo;

import com.duri.rentalplatform.domain.risk.enums.RegistryDataSource;
import java.time.OffsetDateTime;

/**
 * 등기 이력 응답의 머리 부분 매퍼 행. 두 배열은 따로 조회해 응답의 정적 팩토리가 묶는다.
 *
 * @param collectedAt 드라이버 오프셋 그대로다. 서울 오프셋 맞춤은 응답이 한다
 */
public record RegistryHeaderRow(
        RegistryDataSource dataSource,
        OffsetDateTime collectedAt,
        Long propertyId
) {
}
