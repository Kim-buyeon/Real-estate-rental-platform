package com.duri.rentalplatform.domain.risk.entity;

import com.duri.rentalplatform.domain.risk.enums.HouseType;
import com.duri.rentalplatform.domain.risk.vo.PremiumRateBand;
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
 * 보증료율 한 구간 — 데이터베이스 설계서 14절.
 *
 * <p>감사 상위 클래스를 상속하지 않는 사유는 {@link GuaranteeCriteria} 와 같다({@code updated_at} 만 있음).
 */
@Entity
@Getter
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
}
