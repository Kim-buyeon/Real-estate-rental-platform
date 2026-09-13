package com.duri.rentalplatform.domain.risk.entity;

import com.duri.rentalplatform.domain.risk.enums.HouseType;
import com.duri.rentalplatform.domain.risk.vo.PremiumRateBand;
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
 * 보증료율 한 구간 — 데이터베이스 설계서 14절.
 *
 * <p>감사 상위 클래스를 상속하지 않는 사유는 {@link GuaranteeCriteria} 와 같다({@code updated_at} 만 있음).
 */
@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
@Table(name = "guarantee_premium_rate")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuaranteePremiumRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long premiumRateId;

    @Column(nullable = false)
    private Long guaranteeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HouseType houseType;

    @Column(nullable = false)
    private Long depositMin;

    private Long depositMax;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal debtRatioMin;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal debtRatioMax;

    /** 연 보증료율(%). */
    @Column(nullable = false, precision = 5, scale = 3)
    private BigDecimal premiumRate;

    /** 판정기 입력으로 옮긴다. */
    public PremiumRateBand toBand() {
        return new PremiumRateBand(houseType, depositMin, depositMax, debtRatioMin, debtRatioMax, premiumRate);
    }

    /**
     * 수정일시. 기준 수정(ADMIN-01)이 바꿀 때 감사 기능이 채운다. {@code created_at} 이 없어 감사 상위 클래스 대신 이
     * 필드만 매핑한다.
     */
    @LastModifiedDate
    private LocalDateTime updatedAt;

    /** 요율 수정(ADMIN-01). 구간은 바꾸지 않는다. */
    public void changePremiumRate(BigDecimal premiumRate) {
        this.premiumRate = premiumRate;
    }
}
