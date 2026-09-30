package com.duri.rentalplatform.domain.property.vo;

/**
 * 기존 매물 한 건에 채울 건축물대장 조회 키. 갱신 배치(RISK-08)의 적재 단계가 조회 키가 비어 있는 기존 매물을 자연키로 다시
 * 만났을 때 만든다.
 *
 * @param propertyId 매물 식별자
 * @param ledgerKey  채울 조회 키
 */
public record LedgerKeyFill(
        Long propertyId,
        LedgerLookupKey ledgerKey
) {
}
