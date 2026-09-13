package com.duri.rentalplatform.domain.risk.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.risk.enums.OwnershipRightType;
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
 * 갑구 한 건.
 *
 * <p><b>감사 상위 클래스</b> — {@code ownership_history} 에는 {@code updated_at} 이 없고 생성 시각 컬럼 이름이
 * {@code recorded_at} 이다. 이력은 쌓기만 하므로 {@link CreatedAtEntity} 를 상속하고
 * {@code @AttributeOverride} 로 컬럼명을 맞춘다.
 *
 * <p><b>한 행이 등기 한 건이다.</b> 소유권 등기뿐 아니라 압류 · 신탁 같은 갑구 등기도 한 행씩 쌓고
 * {@code right_type} 으로 가른다. 여부 컬럼(압류 · 가압류 · 경매 · 가등기 · 신탁 · 임차권등기명령)은 V1 설계를
 * 따라 남아 있으며 그 행의 등기 목적과 같은 것 하나만 참이다. {@code is_current} 는 소유권 등기에서는
 * 현재 소유자, 그 밖의 등기에서는 말소되지 않았음을 뜻한다 — 응답의 {@code isActive} 다.
 */
@Entity
@Getter
@Table(name = "ownership_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "createdAt", column = @Column(name = "recorded_at", updatable = false))
public class OwnershipHistory extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long ownershipId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registry_id", nullable = false)
    private BuildingRegistry registry;

    @Column(nullable = false)
    private Integer rankNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OwnershipRightType rightType;

    /** 권리자. 컬럼 이름은 V1 그대로다. */
    @Column(nullable = false, length = 50)
    private String ownerName;

    /** 접수일. 컬럼 이름은 V1 그대로다. */
    private LocalDate ownershipDate;

    @Column(length = 50)
    private String registrationCause;

    @Column(name = "provisional_seizure_yn", nullable = false)
    private boolean provisionalSeizure;

    @Column(name = "seizure_yn", nullable = false)
    private boolean seizure;

    @Column(name = "auction_yn", nullable = false)
    private boolean auction;

    @Column(name = "provisional_registration_yn", nullable = false)
    private boolean provisionalRegistration;

    @Column(name = "trust_registration_yn", nullable = false)
    private boolean trustRegistration;

    @Column(name = "lease_registration_yn", nullable = false)
    private boolean leaseRegistration;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    /** 표제부에 갑구 한 건을 적는다. 여부 컬럼은 등기 목적에서 정해진다. */
    public static OwnershipHistory record(
            BuildingRegistry registry,
            int rankNo,
            OwnershipRightType rightType,
            String holderName,
            LocalDate receivedDate,
            String registrationCause,
            boolean isActive) {
        OwnershipHistory history = new OwnershipHistory();
        history.registry = registry;
        history.rankNo = rankNo;
        history.rightType = rightType;
        history.ownerName = holderName;
        history.ownershipDate = receivedDate;
        history.registrationCause = registrationCause;
        history.provisionalSeizure = rightType == OwnershipRightType.PROVISIONAL_SEIZURE;
        history.seizure = rightType == OwnershipRightType.SEIZURE;
        history.auction = rightType == OwnershipRightType.AUCTION_COMMENCEMENT;
        history.provisionalRegistration = rightType == OwnershipRightType.PROVISIONAL_REGISTRATION;
        history.trustRegistration = rightType == OwnershipRightType.TRUST;
        history.leaseRegistration = rightType == OwnershipRightType.TENANCY_REGISTRATION_ORDER;
        history.current = isActive;
        return history;
    }
}
