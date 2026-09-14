package com.duri.rentalplatform.domain.notification.dto.response;

import java.util.List;

/**
 * 알림 목록 응답 — API 명세서(알림) 1.3. 공통 목록 형태({@code CursorPage})에 페이지와 무관한 {@code unreadCount} 가 붙는다.
 */
public record NotificationPageResponse(
        List<NotificationResponse> items,
        String nextCursor,
        boolean hasNext,
        long unreadCount
) {
}
