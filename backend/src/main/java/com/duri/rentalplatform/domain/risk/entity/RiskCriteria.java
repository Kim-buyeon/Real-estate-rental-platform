package com.duri.rentalplatform.domain.risk.entity;

import com.duri.rentalplatform.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 위험 등급 기준(단일 행) — 데이터베이스 설계서 32절, 비즈니스 로직 정의서 3 · 4장. {@code created_at} ·
 * {@code updated_at} 이 모두 있어 {@link BaseEntity} 를 상속한다. 시드 전용이라 정적 팩토리를 두지 않는다.
 */
@Entity
@Getter
@Table(name = "risk_criteria")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskCriteria extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long riskCriteriaId;

    /** 깡통전세 선(%). */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal negativeEquityRatio;

    /** SAFE/CAUTION 경계(%). */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal cautionLeaseRatio;
}
