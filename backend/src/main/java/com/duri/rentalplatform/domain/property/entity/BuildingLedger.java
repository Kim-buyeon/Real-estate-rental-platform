package com.duri.rentalplatform.domain.property.entity;

import com.duri.rentalplatform.common.BaseEntity;
import com.duri.rentalplatform.domain.property.enums.LedgerDataSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 건축물관리대장. 매물과 1:1 이며 {@code property_id} 에 UNIQUE 제약이 있다.
 *
 * <p><b>감사 상위 클래스</b> — {@code building_ledger} 에는 {@code updated_at} 이 있으므로 {@link BaseEntity} 를
 * 상속한다. V1 에는 생성일시 컬럼이 없어 이 규칙을 따를 수 없었고, V5 가 {@code created_at} 을 더했다. 수정일시는
 * 응답의 {@code collectedAt} 이다 — 감사 리스너는 최초 저장 때도 수정일시를 채운다.
 *
 * <p>매물은 연관 엔티티가 아니라 식별자로 갖는다. 위험도 분석이 이 엔티티를 참조하는 방향이지 반대가 아니다.
 */
@Entity
@Getter
@Table(name = "building_ledger")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuildingLedger extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long ledgerId;

    @Column(nullable = false, unique = true)
    private Long propertyId;

    @Column(nullable = false, length = 200)
    private String ledgerAddress;

    @Column(nullable = false, length = 50)
    private String ownerName;

    /** 주용도. 응답의 {@code mainPurpose}. */
    @Column(nullable = false, length = 50)
    private String buildingPurpose;

    @Column(length = 50)
    private String buildingStructure;

    /** 건축면적(㎡). 연면적이 아니다. */
    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal buildingArea;

    /** 연면적(㎡). */
    @Column(precision = 10, scale = 2)
    private BigDecimal totalFloorArea;

    /** 전용면적(㎡). */
    @Column(precision = 7, scale = 2)
    private BigDecimal exclusiveArea;

    /** 사용승인일. */
    private LocalDate approvalDate;

    /** 위반건축물 표기. 응답의 {@code violationBuilding}. */
    @Column(name = "violation_yn", nullable = false)
    private boolean violation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerDataSource dataSource;

    /** 떼어 온 대장을 매물에 붙인다. */
    public static BuildingLedger collect(
            Long propertyId,
            String ledgerAddress,
            String ownerName,
            String buildingPurpose,
            String buildingStructure,
            BigDecimal buildingArea,
            BigDecimal totalFloorArea,
            BigDecimal exclusiveArea,
            LocalDate approvalDate,
            boolean violation,
            LedgerDataSource dataSource) {
        BuildingLedger ledger = new BuildingLedger();
        ledger.propertyId = propertyId;
        ledger.ledgerAddress = ledgerAddress;
        ledger.ownerName = ownerName;
        ledger.buildingPurpose = buildingPurpose;
        ledger.buildingStructure = buildingStructure;
        ledger.buildingArea = buildingArea;
        ledger.totalFloorArea = totalFloorArea;
        ledger.exclusiveArea = exclusiveArea;
        ledger.approvalDate = approvalDate;
        ledger.violation = violation;
        ledger.dataSource = dataSource;
        return ledger;
    }
}
