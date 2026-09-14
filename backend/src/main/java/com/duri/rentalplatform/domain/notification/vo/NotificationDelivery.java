package com.duri.rentalplatform.domain.notification.vo;

import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import java.time.LocalDateTime;

/**
 * 저장이 커밋된 알림 한 건을 발송자에 넘기는 값. 전달 본문은 식별자와 유형으로 한정한다 — API 명세서(알림) 1.1 · 1.4. 상세는
 * 클라이언트가 목록 조회로 가져온다.
 *
 * @param notificationId 알림 공통 행 ID
 * @param userId         받을 사용자 — 발송자가 연결을 고르는 기준이며 전달 본문에는 넣지 않는다
 * @param type           알림 유형
 * @param propertyId     관련 매물 ID
 * @param createdAt      알림 생성 시각. DB 와 같은 서울 벽시계 시각이다
 */
public record NotificationDelivery(
        Long notificationId,
        Long userId,
        NotificationType type,
        Long propertyId,
        LocalDateTime createdAt
) {
}
