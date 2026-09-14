package com.duri.rentalplatform.domain.notification.dto.response;

import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * {@code GET /api/notifications/stream} 이벤트 본문 — API 명세서(알림) 1.4. 식별자와 유형만 담는다. 받을 사용자는 연결이 이미
 * 가리키므로 넣지 않는다. 공통 응답 봉투로 감싸지 않는다 — 명세 1.4 의 본문이 봉투 없는 형태다.
 */
public record NotificationEventResponse(
        Long notificationId,
        NotificationType type,
        Long propertyId,
        OffsetDateTime createdAt
) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 발송 값의 생성 시각은 DB 와 같은 서울 벽시계 시각이다. 서울 오프셋을 붙여 명세 예시({@code +09:00})와 맞춘다. */
    public static NotificationEventResponse from(NotificationDelivery delivery) {
        return new NotificationEventResponse(
                delivery.notificationId(),
                delivery.type(),
                delivery.propertyId(),
                delivery.createdAt() == null ? null : delivery.createdAt().atZone(SEOUL).toOffsetDateTime());
    }
}
