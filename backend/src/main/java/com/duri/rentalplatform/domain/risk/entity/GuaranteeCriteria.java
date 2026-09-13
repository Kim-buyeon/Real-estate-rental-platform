package com.duri.rentalplatform.domain.risk.entity;

import com.duri.rentalplatform.domain.risk.enums.GuaranteeProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 보증기관 공통 판정 기준 — 데이터베이스 설계서 10절. 시드(V7)가 넣고 판정은 읽기만 한다. 값 변경은 ADMIN-01.
 *
 * <p><b>감사 상위 클래스를 상속하지 않는 이유</b> — 테이블에 {@code updated_at} 만 있고 {@code created_at} 이 없다.
 * {@code BaseEntity} 는 둘 다, {@code CreatedAtEntity} 는 {@code created_at} 을 요구하므로 어느 쪽을 상속해도
 * {@code ddl-auto: validate} 가 기동을 막는다. 수정일시는 필드 하나로 매핑하고 감사 리스너를 엔티티에
 * 직접 붙인다 — 기준 수정(ADMIN-01)이 바꿀 때 채워진다. 기준 테이블 7종 중 {@code risk_criteria} 를 뺀 여섯이 같은 사유다.
 *
 * <p>생성 경로가 없어(시드 전용) 정적 팩토리를 두지 않는다. 기준 테이블 7종 모두 같다.
 */
@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
@Table(name = "guarantee_criteria")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuaranteeCriteria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long guaranteeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 10)
    private GuaranteeProvider provider;

    /** 최대 보증 가능 보증금(원). */
    @Column(nullable = false)
    private Long maxDeposit;

    /** 담보인정비율(%). */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal collateralRatio;

    /** 선순위채권 한도(%). null 이면 검사하지 않는다. */
    @Column(precision = 5, scale = 2)
    private BigDecimal seniorDebtRatioLimit;

    @Column(name = "strict_singlehouse_yn", nullable = false)
    private boolean strictSinglehouse;

    @Column(name = "require_confirmed_date_yn", nullable = false)
    private boolean requireConfirmedDate;

    @Column(name = "violation_disqualify_yn", nullable = false)
    private boolean violationDisqualify;

    @Column(name = "right_violation_disqualify_yn", nullable = false)
    private boolean rightViolationDisqualify;

    /**
     * 수정일시. 기준 수정(ADMIN-01)이 바꿀 때 감사 기능이 채운다. {@code created_at} 이 없어 감사 상위 클래스 대신 이
     * 필드만 매핑한다.
     */
    @LastModifiedDate
    private LocalDateTime updatedAt;

    /** 기준 수정(ADMIN-01). 검증 · 이력은 호출자가 한다. */
    public void changeCriteria(BigDecimal collateralRatio, Long maxDeposit, BigDecimal seniorDebtRatioLimit) {
        this.collateralRatio = collateralRatio;
        this.maxDeposit = maxDeposit;
        this.seniorDebtRatioLimit = seniorDebtRatioLimit;
    }
}
