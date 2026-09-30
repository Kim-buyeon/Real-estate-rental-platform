package com.duri.rentalplatform.domain.loan.entity;

import com.duri.rentalplatform.domain.property.enums.PropertyType;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 대출 상품 — 데이터베이스 설계서 18절.
 *
 * <p>행은 두 종류다. V10 시드의 예시 행(주택 유형 · 기준월 없음)과, HF 전세자금대출 금리 API 가 은행 · 주택 유형마다 넣고 고치는
 * 행이다. 한도 계산의 대표 상품 선택은 {@code LoanProductRepository} 가 갖는다.
 *
 * <p>{@code created_at} 없이 {@code updated_at} 만 있어 두 감사 상위 클래스 어느 쪽도 맞지 않는다. 상속하지 않고 감사 리스너를
 * 직접 걸어 {@code updated_at} 을 채운다 — 행을 만들 때도 채워진다(Spring Data 감사는 생성 시 수정일시도 기록한다).
 */
@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
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

    /** 금리 유형. HF 금리 API 행은 제공처가 구분하지 않아 NULL. */
    @Column(length = 10)
    private String rateType;

    /** 상품 한도(원). */
    @Column(nullable = false)
    private Long maxLimit;

    /** 대출 기간(년). HF 금리 API 행은 확인된 상품 값이 없어 NULL. */
    private Integer loanTerm;

    @Column(nullable = false, length = 20)
    private String repaymentType;

    private Long incomeCondition;

    @Column(nullable = false)
    private boolean houseOwnershipCondition;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** 매물 유형. HF 금리 API 행만 있다. 시드 행은 NULL. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PropertyType houseType;

    /** 금리 기준월(그 달 1일). HF 금리 API 행만 있다. */
    private LocalDate baseMonth;

    /** 기준월 대출실행금액(원). 대표 상품 선택 기준. */
    private Long loanAmount;

    /**
     * HF 전세자금대출 금리 API 의 은행 한 곳을 새 행으로 만든다. 상품 속성 중 API 가 주지 않는 값의 출처는 호출부가 갖는다.
     *
     * @param maxLimit 상품 한도(원)
     */
    public static LoanProduct hfJeonseRate(String bankName, String productName, String loanType,
            String repaymentType, boolean houseOwnershipCondition, PropertyType houseType, LocalDate baseMonth,
            BigDecimal interestRate, long loanAmount, long maxLimit) {
        LoanProduct product = new LoanProduct();
        product.bankName = bankName;
        product.productName = productName;
        product.loanType = loanType;
        product.repaymentType = repaymentType;
        product.houseOwnershipCondition = houseOwnershipCondition;
        product.houseType = houseType;
        product.changeRate(baseMonth, interestRate, loanAmount, maxLimit);
        return product;
    }

    /** 새 기준월의 금리 · 실행금액으로 고친다. 상품 한도는 갱신 시점의 기준값을 다시 넣는다. */
    public void changeRate(LocalDate baseMonth, BigDecimal interestRate, long loanAmount, long maxLimit) {
        this.baseMonth = baseMonth;
        this.interestRate = interestRate;
        this.loanAmount = loanAmount;
        this.maxLimit = maxLimit;
    }
}
