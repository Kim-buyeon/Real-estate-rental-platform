package com.duri.rentalplatform.domain.notification.entity;

import com.duri.rentalplatform.common.CreatedAtEntity;
import com.duri.rentalplatform.domain.notification.enums.NotificationType;
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
 * 알림 공통 행 — 데이터베이스 설계서 25절. {@code updated_at} 이 없어 {@link CreatedAtEntity} 를 상속한다.
 *
 * <p>유형별 상세는 하위 테이블이 갖는다. 사용자는 식별자로 갖고 연관을 따라가지 않는다 — 목록은 매퍼가 조인한다(NOTI-05).
 */
@Entity
@Getter
@Table(name = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long notifId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notif_type", nullable = false, length = 20)
    private NotificationType type;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    /** 읽지 않은 새 알림. */
    public static Notification unread(Long userId, NotificationType type) {
        Notification notification = new Notification();
        notification.userId = userId;
        notification.type = type;
        notification.read = false;
        return notification;
    }

    /** 사용자가 확인했다. 이미 읽었으면 그대로다. */
    public void markRead() {
        this.read = true;
    }
}
