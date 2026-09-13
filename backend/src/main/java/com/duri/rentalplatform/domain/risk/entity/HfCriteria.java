package com.duri.rentalplatform.domain.risk.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * HF 세부 기준 — 데이터베이스 설계서 12절.
 *
 * <p>감사 상위 클래스를 상속하지 않는 사유는 {@link GuaranteeCriteria} 와 같다({@code updated_at} 만 있음).
 */
@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
@Table(name = "hf_criteria")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HfCriteria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long hfCriteriaId;

    @Column(nullable = false)
    private Long guaranteeId;

    /** 전세자금보증부 대출 연계 필요. */
    @Column(name = "loan_linked_required_yn", nullable = false)
    private boolean loanLinkedRequired;

    @Column(name = "lowest_premium_yn", nullable = false)
    private boolean lowestPremium;

    @Column(name = "youth_discount_yn", nullable = false)
    private boolean youthDiscount;

    @Column(name = "newlywed_discount_yn", nullable = false)
    private boolean newlywedDiscount;

    /**
     * 수정일시. 기준 수정(ADMIN-01)이 바꿀 때 감사 기능이 채운다. {@code created_at} 이 없어 감사 상위 클래스 대신 이
     * 필드만 매핑한다.
     */
    @LastModifiedDate
    private LocalDateTime updatedAt;

    /** 대출 연계 필요 여부 수정(ADMIN-01). */
    public void changeLoanLinkedRequired(boolean loanLinkedRequired) {
        this.loanLinkedRequired = loanLinkedRequired;
    }
}
