package com.duri.rentalplatform.domain.property.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.property.enums.WishlistAlertCondition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관심 매물 — 데이터베이스 설계서 23절. {@code updated_at} 이 없어 {@link CreatedAtEntity} 를 상속한다.
 *
 * <p>사용자 · 매물은 식별자로 갖는다. 등록 · 해제만 하고 연관을 따라가지 않는다 — 목록은 매퍼가 조인한다. 사용자는
 * 토큰에서 온 식별자라 엔티티를 읽을 이유가 없다.
 */
@Entity
@Getter
@Table(name = "wishlist")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wishlist extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long wishId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long propertyId;

    /** 모니터링 알림 대상 여부. 관심 매물은 알림 발송 범위다 — 수신 여부는 알림 구독(NOTI-01)이 일괄로 끈다. */
    @Column(name = "monitoring_yn", nullable = false)
    private boolean monitoring;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private WishlistAlertCondition alertCondition;

    /** 등록. 모니터링을 켜고 1단계 트리거 둘(위험도 변경 · 등기 변동)을 조건으로 둔다. */
    public static Wishlist register(Long userId, Long propertyId) {
        Wishlist wishlist = new Wishlist();
        wishlist.userId = userId;
        wishlist.propertyId = propertyId;
        wishlist.monitoring = true;
        wishlist.alertCondition = WishlistAlertCondition.RISK_AND_REGISTRY;
        return wishlist;
    }
}
