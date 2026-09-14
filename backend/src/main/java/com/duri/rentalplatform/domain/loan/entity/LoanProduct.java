package com.duri.rentalplatform.domain.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대출 상품 — 데이터베이스 설계서 18절. {@code updated_at} 만 있어 감사 상위 클래스를 상속하지 않는다. 시드 전용이다.
 */
@Entity
@Getter
@Table(name = "loan_product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long loanId;

    @Column(nullable = false, length = 50)
    private String bankName;

    @Column(nullable = false, length = 100)
    private String productName;

    @Column(nullable = false, length = 30)
    private String loanType;

    /** 금리(%). */
    @Column(nullable = false, precision = 5, scale = 3)
    private BigDecimal interestRate;

    @Column(nullable = false, length = 10)
    private String rateType;

    /** 상품 한도(원). */
    @Column(nullable = false)
    private Long maxLimit;

    /** 대출 기간(년). */
    @Column(nullable = false)
    private Integer loanTerm;

    @Column(nullable = false, length = 20)
    private String repaymentType;

    private Long incomeCondition;

    @Column(nullable = false)
    private boolean houseOwnershipCondition;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
