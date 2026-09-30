package com.duri.rentalplatform.external.buildingledger;

import com.duri.rentalplatform.domain.property.enums.PropertyType;
import com.duri.rentalplatform.domain.property.vo.LedgerLookupKey;
import com.duri.rentalplatform.domain.property.vo.PropertyNaturalKey;

/**
 * 건축물대장을 떼어 볼 매물. 수집 서비스가 매물에서 옮겨 담아 넘긴다.
 *
 * <p>Real 은 조회 키(시군구 · 법정동 코드와 번 · 지)로 조회하고, 여러 동 중 하나를 고를 때 주소 · 매물 유형을 쓴다. Mock 은
 * 조회 키를 쓰지 않고 매물에 어울리는 대장을 만든다 — 주소 · 전용면적은 자연키에 있고, 소유자 불일치 여부는 임대인명 생성과
 * 같은 자연키에서 파생해야 한다({@code LandlordNameGenerator}).
 *
 * @param propertyId   매물 ID. Mock 이 대장 내용을 고르는 씨앗이다
 * @param naturalKey   매물 자연키. 주소와 전용면적을 담는다
 * @param landlordName 임대인명
 * @param propertyType 매물 유형. Mock 은 대장상 주용도를 정하고, Real 은 동을 고르는 데 쓴다
 * @param ledgerKey    건축물대장 조회 키. 없으면 null — Real 은 조회하지 않고 빈 값을 돌려준다
 */
public record BuildingLedgerLookup(
        Long propertyId,
        PropertyNaturalKey naturalKey,
        String landlordName,
        PropertyType propertyType,
        LedgerLookupKey ledgerKey
) {
}
