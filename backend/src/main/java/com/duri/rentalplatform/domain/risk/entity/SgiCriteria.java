package com.duri.rentalplatform.domain.risk.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SGI 세부 기준 — 데이터베이스 설계서 13절.
 *
 * <p>감사 상위 클래스를 상속하지 않는 사유는 {@link GuaranteeCriteria} 와 같다({@code updated_at} 만 있음).
 */
@Entity
@Getter
@Table(name = "sgi_criteria")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SgiCriteria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sgiCriteriaId;

    @Column(nullable = false)
    private Long guaranteeId;

    /** 아파트 보증금 한도 없음. */
    @Column(name = "apartment_unlimited_yn", nullable = false)
    private boolean apartmentUnlimited;

    @Column(nullable = false)
    private Long otherHouseLimit;

    @Column(name = "private_insurer_yn", nullable = false)
    private boolean privateInsurer;

    @Column(name = "high_value_available_yn", nullable = false)
    private boolean highValueAvailable;

    @Column(name = "broker_contract_required_yn", nullable = false)
    private boolean brokerContractRequired;
}
