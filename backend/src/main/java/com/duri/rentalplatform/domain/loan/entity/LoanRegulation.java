package com.duri.rentalplatform.domain.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 대출 규제 기준 — 데이터베이스 설계서 17절, 비즈니스 로직 정의서 6장. 시각 컬럼이 없어 감사 상위 클래스를 상속하지 않는다.
 * 행은 시드가 넣고 애플리케이션은 수치만 고친다(ADMIN-01 대출 규제 수정). 행을 만들지 않으므로 정적 팩토리를 두지 않는다.
 */
@Entity
@Getter
@Table(name = "loan_regulation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanRegulation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long regulationId;

    @Column(nullable = false, length = 30)
    private String houseType;

    @Column(nullable = false, length = 30)
    private String regionType;

    /** 주택 보유자 전세대출 이자상환분 DSR 한도(%). */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal dsrLimit;

    /** 스트레스 금리 가산율(%p). 참고 한도 계산용. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal stressDsrRate;

    /** DTI 참고 수치(%). 한도 판정에 쓰지 않는다. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal dtiLimit;

    @Column(nullable = false)
    private LocalDate effectiveDate;

    /** 임차보증금 대비 대출 비율(%). */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal depositRatioLimit;

    /** 보증기관 상한 — 무주택(원). */
    @Column(nullable = false)
    private Long guaranteeCapNoHouse;

    /** 보증기관 상한 — 주택 보유(원). */
    @Column(nullable = false)
    private Long guaranteeCapOneHouse;

    /** 관리자 수정 — 수치 여섯만 바꾼다. 적용 대상(주택 · 지역 유형, 시행일)은 그대로 둔다. */
    public void changeLimits(BigDecimal depositRatioLimit, Long guaranteeCapNoHouse, Long guaranteeCapOneHouse,
            BigDecimal dsrLimit, BigDecimal stressDsrRate, BigDecimal dtiLimit) {
        this.depositRatioLimit = depositRatioLimit;
        this.guaranteeCapNoHouse = guaranteeCapNoHouse;
        this.guaranteeCapOneHouse = guaranteeCapOneHouse;
        this.dsrLimit = dsrLimit;
        this.stressDsrRate = stressDsrRate;
        this.dtiLimit = dtiLimit;
    }
}
