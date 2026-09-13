package com.duri.rentalplatform.domain.risk.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 명의 · 문서 정합 확인(RISK-04)의 입력.
 *
 * @param ownerships            갑구 전체. 현재 소유자는 계산기가 고른다
 * @param landlordName          임대인명 — {@code property.landlord_name}
 * @param ledgerAddress         대장 주소 — {@code building_ledger.ledger_address}
 * @param registryAddress       등기 표제부 주소 — {@code building_registry.registry_address}. 없으면 null
 * @param ledgerExclusiveArea   대장 전용면적(㎡) — {@code building_ledger.exclusive_area}. 없으면 null
 * @param registryExclusiveArea 등기 표제부 전용면적(㎡) — {@code building_registry.exclusive_area}. 없으면 null
 * @param violationBuilding     위반건축물인가 — {@code building_ledger.violation_yn}
 */
public record ConsistencyInput(
        List<OwnershipRightEntry> ownerships,
        String landlordName,
        String ledgerAddress,
        String registryAddress,
        BigDecimal ledgerExclusiveArea,
        BigDecimal registryExclusiveArea,
        boolean violationBuilding
) {

    public ConsistencyInput {
        ownerships = List.copyOf(ownerships);
    }
}
