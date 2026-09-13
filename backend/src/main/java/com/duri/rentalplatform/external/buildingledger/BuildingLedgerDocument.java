package com.duri.rentalplatform.external.buildingledger;

import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 건축물대장 한 통. <b>외부 응답 형태가 아니라 우리가 쓰는 형태</b>다 — 면적은 ㎡ 단위 {@link BigDecimal}, 일자는
 * {@link LocalDate}, 위반건축물 표기는 논리값으로 정규화가 끝난 값이다.
 *
 * @param ledgerAddress     대장상 건물 주소
 * @param ownerName         대장상 소유자명
 * @param buildingPurpose   주용도
 * @param buildingStructure 구조
 * @param buildingArea      건축면적(㎡)
 * @param totalFloorArea    연면적(㎡)
 * @param exclusiveArea     전용면적(㎡)
 * @param approvalDate      사용승인일
 * @param violation         위반건축물 표기
 * @param dataSource        수집 출처
 */
public record BuildingLedgerDocument(
        String ledgerAddress,
        String ownerName,
        String buildingPurpose,
        String buildingStructure,
        BigDecimal buildingArea,
        BigDecimal totalFloorArea,
        BigDecimal exclusiveArea,
        LocalDate approvalDate,
        boolean violation,
        LedgerDataSource dataSource
) {
}
