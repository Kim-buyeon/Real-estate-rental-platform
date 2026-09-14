package com.duri.rentalplatform.domain.notification.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.notification.enums.WishlistChangeType;
import jakarta.persistence.AttributeOverride;
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
 * 관심 매물 모니터링 알림 — 데이터베이스 설계서 29절.
 *
 * <p><b>감사 상위 클래스</b> — {@code updated_at} 이 없고 생성 시각 컬럼 이름이 {@code detected_at} 이다. 알림은 변동을 반영한
 * 트랜잭션이 커밋된 뒤에 만들어지므로 행이 생기는 시각이 곧 감지를 기록한 시각이다. {@link CreatedAtEntity} 를 상속하고
 * {@code @AttributeOverride} 로 컬럼명을 맞춘다.
 *
 * <p><b>매물 · 관심 매물</b> — 식별자로 갖는다. {@code wish_id} 는 관심 해제 시 NULL 이 되고({@code ON DELETE SET NULL}, V13)
 * 이력은 {@code property_id} 로 매물을 계속 가리킨다. 그래서 {@code wishId} 는 비어 있을 수 있다.
 */
@Entity
@Getter
@Table(name = "wishlist_notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AttributeOverride(name = "createdAt", column = @Column(name = "detected_at", updatable = false))
public class WishlistNotification extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long wishNotifId;

    @Column(nullable = false)
    private Long notifId;

    @Column(nullable = false)
    private Long propertyId;

    /** 관심 해제 뒤에는 NULL. */
    private Long wishId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WishlistChangeType changeType;

    @Column(nullable = false, length = 100)
    private String beforeValue;

    @Column(nullable = false, length = 100)
    private String afterValue;

    public static WishlistNotification of(Long notifId, Long propertyId, Long wishId, WishlistChangeType changeType,
            String beforeValue, String afterValue) {
        WishlistNotification notification = new WishlistNotification();
        notification.notifId = notifId;
        notification.propertyId = propertyId;
        notification.wishId = wishId;
        notification.changeType = changeType;
        notification.beforeValue = beforeValue;
        notification.afterValue = afterValue;
        return notification;
    }
}
