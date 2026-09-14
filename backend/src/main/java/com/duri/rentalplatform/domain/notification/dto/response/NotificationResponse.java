package com.duri.rentalplatform.domain.notification.dto.response;

import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.vo.NotificationRow;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 알림 목록 한 건 — API 명세서(알림) 1.3. */
public record NotificationResponse(
        Long notificationId,
        NotificationType type,
        String title,
        Long propertyId,
        String beforeValue,
        String afterValue,
        boolean isRead,
        OffsetDateTime createdAt
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 제목은 유형의 고정 문구, 시각은 서울 오프셋 — 공통 규약 1.1. */
    public static NotificationResponse of(NotificationRow row) {
        return new NotificationResponse(
                row.notificationId(),
                row.type(),
                row.type().getTitle(),
                row.propertyId(),
                row.beforeValue(),
                row.afterValue(),
                row.read(),
                row.createdAt().atZoneSameInstant(SEOUL).toOffsetDateTime());
    }
}
