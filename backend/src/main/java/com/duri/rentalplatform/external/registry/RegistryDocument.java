package com.duri.rentalplatform.external.registry;

import com.duri.rentalplatform.domain.risk.enums.MortgageRightType;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
import com.duri.rentalplatform.domain.risk.enums.RegistryDataSource;
import java.time.LocalDate;
import java.util.List;

/**
 * 등기부등본 한 통. <b>외부 응답 형태가 아니라 우리가 쓰는 형태</b>다 — 등기 목적은 우리 열거형으로,
 * 금액은 원 단위로, 접수일은 {@link LocalDate} 로 정규화가 끝난 값이다.
 *
 * @param buildingPurpose   표제부 건물 용도
 * @param buildingStructure 표제부 건물 구조
 * @param dataSource        수집 출처
 * @param ownerships        갑구. 순위번호 순
 * @param mortgages         을구. 순위번호 순
 */
public record RegistryDocument(
        String buildingPurpose,
        String buildingStructure,
        RegistryDataSource dataSource,
        List<OwnershipEntry> ownerships,
        List<MortgageEntry> mortgages
) {

    public RegistryDocument {
        ownerships = List.copyOf(ownerships);
        mortgages = List.copyOf(mortgages);
    }

    /**
     * 갑구 한 건.
     *
     * @param rankNo       순위번호
     * @param rightType    등기 목적
     * @param holderName   권리자. 소유권 등기면 소유자, 압류 · 가압류면 채권자, 신탁이면 수탁자
     * @param receivedDate 접수일
     * @param cause        등기원인
     * @param isActive     말소되지 않았는가. 소유권 등기는 현재 소유자만 참이다
     */
    public record OwnershipEntry(
            int rankNo,
            OwnershipRightType rightType,
            String holderName,
            LocalDate receivedDate,
            String cause,
            boolean isActive
    ) {
    }

    /**
     * 을구 한 건.
     *
     * @param rankNo             순위번호
     * @param rightType          등기 목적
     * @param creditor           근저당권자 · 임차인
     * @param debtorName         채무자
     * @param receivedDate       접수일
     * @param cause              등기원인
     * @param loanAmount         대출 원금(원). 임차권이면 0
     * @param maxClaimAmount     채권최고액(원). 임차권이면 0
     * @param priorTenantDeposit 선순위 임차보증금(원). 근저당이면 0
     * @param isActive           말소되지 않았는가
     */
    public record MortgageEntry(
            int rankNo,
            MortgageRightType rightType,
            String creditor,
            String debtorName,
            LocalDate receivedDate,
            String cause,
            long loanAmount,
            long maxClaimAmount,
            long priorTenantDeposit,
            boolean isActive
    ) {
    }
}
