package com.duri.rentalplatform.external.buildingledger;

import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 건축물대장 한 통. <b>외부 응답 형태가 아니라 우리가 쓰는 형태</b>다 — 면적은 ㎡ 단위 {@link BigDecimal}, 일자는
 * {@link LocalDate} 로 정규화가 끝난 값이다.
 *
 * <p><b>null 은 「대장에서 확인하지 못함」</b>이다. 출처가 주지 않는 항목을 그럴듯한 값으로 채우지 않는다 — 특히
 * {@code violation} 은 보증 판정 입력이라, 확인하지 않은 「위반 아님」이 가입 가능의 근거가 된다.
 *
 * @param ledgerAddress     대장상 건물 주소. Real 은 지번 주소(대지위치)이며 수집 서비스가 정규화한다
 *                          ({@link LedgerDataSource#isAddressNormalizationRequired()})
 * @param ownerName         대장상 소유자명. 건축HUB 는 주지 않는다(null)
 * @param buildingPurpose   주용도
 * @param buildingStructure 구조
 * @param buildingArea      건축면적(㎡). 미기재면 null
 * @param totalFloorArea    연면적(㎡). 미기재면 null
 * @param exclusiveArea     전용면적(㎡). 호를 특정하지 못하면 null
 * @param approvalDate      사용승인일. 미기재면 null
 * @param violation         위반건축물 표기. 확인하지 못하면 null — 건축HUB 는 이 항목을 주지 않는다
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
        Boolean violation,
        LedgerDataSource dataSource
) {
}
