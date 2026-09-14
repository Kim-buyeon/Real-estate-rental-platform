package com.duri.rentalplatform.domain.notification.dto.condition;

/**
 * 알림 목록 조회 조건. 커서 문자열을 마지막 행의 알림 식별자로 푼 값이다.
 *
 * @param userId 인증 사용자
 * @param lastNotificationId 이전 페이지 마지막 행의 {@code notif_id}. 첫 페이지는 null
 * @param limit 다음 페이지 판정을 위해 요청 크기 + 1
 */
public record NotificationListCondition(
        Long userId,
        Long lastNotificationId,
        int limit
) {
}
