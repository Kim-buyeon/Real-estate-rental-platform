package com.duri.rentalplatform.domain.notification.vo;

import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import java.time.OffsetDateTime;

/**
 * 알림 목록의 매퍼 행 — API 명세서(알림) 1.3. {@code title} 은 유형이 정하므로 응답이 채운다.
 *
 * @param createdAt 드라이버 오프셋 그대로다. 서울 오프셋 맞춤은 응답이 한다
 */
public record NotificationRow(
        Long notificationId,
        NotificationType type,
        Long propertyId,
        String beforeValue,
        String afterValue,
        boolean read,
        OffsetDateTime createdAt
) {
}
