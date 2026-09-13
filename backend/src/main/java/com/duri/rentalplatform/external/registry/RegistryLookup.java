package com.duri.rentalplatform.external.registry;

import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;

/**
 * 등기를 떼어 볼 매물. 수집 서비스가 매물에서 옮겨 담아 넘긴다.
 *
 * <p>실제 중계 서비스라면 주소 하나로 조회한다. 나머지는 Mock 이 매물에 어울리는 등기를 만들기 위한 값이다 —
 * 소유자 불일치 여부는 임대인명 생성과 같은 자연키에서 파생해야 하고({@code LandlordNameGenerator}),
 * 근저당 금액은 시세에 비례해야 깡통전세 판정(RISK-02)이 한쪽으로 쏠리지 않는다.
 *
 * @param propertyId   매물 ID. Mock 이 등기 내용을 고르는 씨앗이다
 * @param naturalKey   매물 자연키. 주소를 담는다
 * @param landlordName 임대인명
 * @param propertyType 매물 유형. 등기상 건물 용도를 정한다
 * @param marketPrice  시세(원)
 */
public record RegistryLookup(
        Long propertyId,
        PropertyNaturalKey naturalKey,
        String landlordName,
        PropertyType propertyType,
        Long marketPrice
) {
}
