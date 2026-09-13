package com.duri.rentalplatform.domain.risk.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.property.enums.RiskGrade;
import com.duri.rentalplatform.domain.risk.enums.GradeReason;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 위험도 분석 한 건(이력성) — 데이터베이스 설계서 16절. 결론만 저장하고 판정 근거는 저장하지 않는다.
 *
 * <p><b>감사 상위 클래스</b> — {@code updated_at} 이 없고 생성 시각 컬럼 이름이 {@code analyzed_at} 이다.
 * {@link CreatedAtEntity} 를 상속하고 컬럼명을 맞춘다.
 *
 * <p>매물 · 등기 · 대장 · 보증기관은 식별자로 갖는다. 다른 도메인의 엔티티를 끌어오지 않고, 이력 행에서 연관을
 * 탐색하는 경로도 없다.
 */
@Entity
@Getter
@Table(name = "risk_analysis")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "createdAt", column = @Column(name = "analyzed_at", updatable = false))
public class RiskAnalysis extends CreatedAtEntity {

    /** 전세가율 컬럼 NUMERIC(5,2) 의 최댓값. 999% 초과는 이미 DANGER 이고 표시값은 응답이 따로 낸다. */
    public static final BigDecimal MAX_STORED_LEASE_RATIO = new BigDecimal("999.99");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long riskId;

    @Column(nullable = false)
    private Long propertyId;

    @Column(nullable = false)
    private Long registryId;

    @Column(nullable = false)
    private Long ledgerId;

    /** 가입 가능한 첫 기관(HUG → HF → SGI). 없으면 null. */
    private Long eligibleGuaranteeId;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal leaseRatio;

    @Column(name = "hug_eligible_yn", nullable = false)
    private boolean hugEligible;

    @Column(name = "hf_eligible_yn", nullable = false)
    private boolean hfEligible;

    @Column(name = "sgi_eligible_yn", nullable = false)
    private boolean sgiEligible;

    @Column(name = "insurance_eligible_yn", nullable = false)
    private boolean insuranceEligible;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RiskGrade riskGrade;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private RiskGrade previousGrade;

    /** 등급 사유 열거값 이름. 컬럼이 TEXT 라 열거형 매핑이 아니라 문자열로 둔다. */
    @Column(columnDefinition = "text")
    private String riskReason;

    @Column(name = "is_latest", nullable = false)
    private boolean latest;

    /**
     * 새 분석 결과를 최신 행으로 적는다.
     *
     * @param leaseRatio    전세가율(%). 컬럼 상한을 넘으면 {@link #MAX_STORED_LEASE_RATIO} 로 자른다
     * @param previousGrade 직전 최신 행의 등급. 첫 분석이면 null
     */
    public static RiskAnalysis record(
            Long propertyId,
            Long registryId,
            Long ledgerId,
            Long eligibleGuaranteeId,
            BigDecimal leaseRatio,
            boolean hugEligible,
            boolean hfEligible,
            boolean sgiEligible,
            RiskGrade riskGrade,
            GradeReason gradeReason,
            RiskGrade previousGrade) {
        RiskAnalysis analysis = new RiskAnalysis();
        analysis.propertyId = propertyId;
        analysis.registryId = registryId;
        analysis.ledgerId = ledgerId;
        analysis.eligibleGuaranteeId = eligibleGuaranteeId;
        analysis.leaseRatio = storedLeaseRatio(leaseRatio);
        analysis.hugEligible = hugEligible;
        analysis.hfEligible = hfEligible;
        analysis.sgiEligible = sgiEligible;
        analysis.insuranceEligible = hugEligible || hfEligible || sgiEligible;
        analysis.riskGrade = riskGrade;
        analysis.riskReason = gradeReason.name();
        analysis.previousGrade = previousGrade;
        analysis.latest = true;
        return analysis;
    }

    /** 컬럼에 담기는 전세가율. 저장 여부 비교도 이 값으로 한다. */
    public static BigDecimal storedLeaseRatio(BigDecimal leaseRatio) {
        return leaseRatio.compareTo(MAX_STORED_LEASE_RATIO) > 0 ? MAX_STORED_LEASE_RATIO : leaseRatio;
    }

    /** 새 분석이 최신이 되어 이 행을 이력으로 내린다. */
    public void supersede() {
        this.latest = false;
    }

    /** 등급 · 3사 가입 · 저장 전세가율이 모두 같은가. 같으면 새 이력을 남기지 않는다. */
    public boolean sameConclusion(RiskGrade grade, boolean hug, boolean hf, boolean sgi, BigDecimal leaseRatio) {
        return riskGrade == grade
                && hugEligible == hug
                && hfEligible == hf
                && sgiEligible == sgi
                && this.leaseRatio.compareTo(storedLeaseRatio(leaseRatio)) == 0;
    }
}
