package com.duri.rentalplatform.domain.notification.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.notification.enums.SubscriptionType;
import com.duri.rentalplatform.domain.property.enums.ContractType;
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
 * 알림 구독 설정 한 행 — 데이터베이스 설계서 24절. {@code updated_at} 이 없어 {@link CreatedAtEntity} 를 상속한다.
 *
 * <p>수정은 사용자 행 전체를 지우고 새로 넣는다(API 명세서(알림) 1.2 「전체를 전달」). 그래서 변경 메서드가 없다.
 * 신규 매물은 자치구마다 한 행이고, 조건이 없는 유형은 조건 컬럼이 NULL 인 한 행이다.
 */
@Entity
@Getter
@Table(name = "notification_subscription")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSubscription extends CreatedAtEntity {

    /** 명세에 보증금 하한이 없다. 컬럼 기본값과 같은 0 을 넣는다. */
    private static final long NO_DEPOSIT_MIN = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long subscriptionId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionType subscriptionType;

    /** 자치구명(「강서구」). 신규 매물 외 유형과 자치구 없이 끈 신규 매물은 NULL. */
    @Column(length = 30)
    private String targetDistrict;

    /** NULL 이면 계약 유형 전체. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ContractType contractType;

    @Column(nullable = false)
    private long depositMin;

    /** NULL 이면 상한 없음. */
    private Long depositMax;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** 조건 없는 유형(금리 변동 · 관심 매물 모니터링 · 상담 일정). */
    public static NotificationSubscription toggle(Long userId, SubscriptionType type, boolean active) {
        return create(userId, type, null, null, null, active);
    }

    /** 신규 매물 한 자치구. 자치구 없이 끈 설정이면 {@code district} 가 null 이다. */
    public static NotificationSubscription newProperty(Long userId, String district, ContractType contractType,
            Long depositMax, boolean active) {
        return create(userId, SubscriptionType.NEW_PROPERTY, district, contractType, depositMax, active);
    }

    private static NotificationSubscription create(Long userId, SubscriptionType type, String district,
            ContractType contractType, Long depositMax, boolean active) {
        NotificationSubscription subscription = new NotificationSubscription();
        subscription.userId = userId;
        subscription.subscriptionType = type;
        subscription.targetDistrict = district;
        subscription.contractType = contractType;
        subscription.depositMin = NO_DEPOSIT_MIN;
        subscription.depositMax = depositMax;
        subscription.active = active;
        return subscription;
    }
}
