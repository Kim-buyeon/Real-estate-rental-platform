package com.duri.rentalplatform.domain.notification.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * {@code GET /api/notifications} 파라미터 — API 명세서(알림) 1.3, 공통 규약 1.4.
 *
 * <p>{@code size} 하한은 명세에 없다. 0 이하는 LIMIT 을 깨므로 다른 목록과 같이 1 이상으로 막는다.
 */
public record NotificationListRequest(
        String cursor,
        @Min(1) @Max(NotificationListRequest.MAX_SIZE) Integer size
) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public NotificationListRequest {
        if (size == null) {
            size = DEFAULT_SIZE;
        }
    }
}
