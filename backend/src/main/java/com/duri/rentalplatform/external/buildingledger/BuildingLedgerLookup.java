package com.duri.rentalplatform.external.buildingledger;

import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;

/**
 * 건축물대장을 떼어 볼 매물. 수집 서비스가 매물에서 옮겨 담아 넘긴다.
 *
 * <p>실제 연동이라면 시군구 · 법정동 코드와 번 · 지로 조회한다. 매물이 그 값을 저장하지 않아 지금은 Mock 이 매물에
 * 어울리는 대장을 만들기 위한 값만 담는다 — 주소 · 전용면적은 자연키에 있고, 소유자 불일치 여부는 임대인명 생성과
 * 같은 자연키에서 파생해야 한다({@code LandlordNameGenerator}).
 *
 * @param propertyId   매물 ID. Mock 이 대장 내용을 고르는 씨앗이다
 * @param naturalKey   매물 자연키. 주소와 전용면적을 담는다
 * @param landlordName 임대인명
 * @param propertyType 매물 유형. 대장상 주용도를 정한다
 */
public record BuildingLedgerLookup(
        Long propertyId,
        PropertyNaturalKey naturalKey,
        String landlordName,
        PropertyType propertyType
) {
}
