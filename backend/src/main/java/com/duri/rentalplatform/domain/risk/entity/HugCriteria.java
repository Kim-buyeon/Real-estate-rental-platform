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
 * HUG 세부 기준 — 데이터베이스 설계서 11절. 할인 여부는 보증료 할인이며 가입 판정에 쓰이지 않아 저장소를 두지 않는다.
 *
 * <p>감사 상위 클래스를 상속하지 않는 사유는 {@link GuaranteeCriteria} 와 같다({@code updated_at} 만 있음).
 * 기관 기준은 식별자로 갖는다 — 기준 테이블은 읽기만 하고 연관 탐색이 필요 없다.
 */
@Entity
@Getter
@Table(name = "hug_criteria")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HugCriteria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long hugCriteriaId;

    @Column(nullable = false)
    private Long guaranteeId;

    @Column(nullable = false)
    private Long metroDepositLimit;

    @Column(name = "ltv_premium_tiered_yn", nullable = false)
    private boolean ltvPremiumTiered;

    @Column(name = "newlywed_discount_yn", nullable = false)
    private boolean newlywedDiscount;

    @Column(name = "multichild_discount_yn", nullable = false)
    private boolean multichildDiscount;

    @Column(name = "social_discount_yn", nullable = false)
    private boolean socialDiscount;
}
