package com.duri.rentalplatform.domain.risk.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.risk.enums.MortgageRightType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 을구 한 건.
 *
 * <p><b>감사 상위 클래스</b> — {@code mortgage_history} 에는 {@code updated_at} 이 없고 생성 시각 컬럼 이름이
 * {@code recorded_at} 이다. {@link CreatedAtEntity} 를 상속하고 {@code @AttributeOverride} 로 컬럼명을 맞춘다.
 *
 * <p><b>금액을 {@code Long} 으로 두는 이유</b> — 스키마가 BIGINT 다. 사유는 매물 엔티티의 같은 설명을 따른다.
 */
@Entity
@Getter
@Table(name = "mortgage_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "createdAt", column = @Column(name = "recorded_at", updatable = false))
public class MortgageHistory extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long mortgageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registry_id", nullable = false)
    private BuildingRegistry registry;

    @Column(nullable = false)
    private Integer priorityNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MortgageRightType rightType;

    private LocalDate receiptDate;

    @Column(length = 50)
    private String registrationCause;

    private Long mortgageAmount;

    @Column(length = 100)
    private String mortgageCreditor;

    @Column(length = 50)
    private String debtorName;

    @Column(nullable = false)
    private Long maxBondAmount;

    private Long priorTenantDeposit;

    @Column(name = "lease_right_yn", nullable = false)
    private boolean leaseRight;

    @Column(name = "tenancy_right_yn", nullable = false)
    private boolean tenancyRight;

    @Column(name = "senior_debt_yn", nullable = false)
    private boolean seniorDebt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /**
     * 표제부에 을구 한 건을 적는다.
     *
     * <p><b>선순위 여부는 늘 참이다.</b> 선순위채권은 순위번호가 임차인보다 앞선 항목이다(비즈니스 로직 정의서
     * 4장). 등기를 떼는 시점의 모든 등기는 이제 계약하려는 임차인보다 먼저 접수되어 있으므로 등기에 적힌 항목은
     * 전부 앞선다. 말소 여부는 {@code is_active} 가 따로 가른다.
     */
    public static MortgageHistory record(
            BuildingRegistry registry,
            int rankNo,
            MortgageRightType rightType,
            String creditor,
            String debtorName,
            LocalDate receivedDate,
            String registrationCause,
            long loanAmount,
            long maxClaimAmount,
            long priorTenantDeposit,
            boolean isActive) {
        MortgageHistory history = new MortgageHistory();
        history.registry = registry;
        history.priorityNo = rankNo;
        history.rightType = rightType;
        history.mortgageCreditor = creditor;
        history.debtorName = debtorName;
        history.receiptDate = receivedDate;
        history.registrationCause = registrationCause;
        history.mortgageAmount = loanAmount;
        history.maxBondAmount = maxClaimAmount;
        history.priorTenantDeposit = priorTenantDeposit;
        history.leaseRight = false;
        history.tenancyRight = rightType == MortgageRightType.TENANCY;
        history.seniorDebt = true;
        history.active = isActive;
        return history;
    }
}
