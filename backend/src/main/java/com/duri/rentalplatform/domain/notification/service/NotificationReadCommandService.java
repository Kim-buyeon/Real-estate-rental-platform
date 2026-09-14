package com.duri.rentalplatform.domain.notification.service;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.notification.entity.Notification;
import com.duri.rentalplatform.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 읽음 처리 — API 명세서(알림) 1.3 「읽음 처리」. 생성({@link NotificationCommandService})과 의존이 달라 나눈다 — 생성은 커밋 후
 * 비동기 · 분산 락 · 중복 키를 쓰고, 읽음은 사용자 요청 한 건의 단순 변경이다.
 *
 * <p>읽음은 사용자가 확인한 시점에 표시한다(아키텍처 설계서(알림 전달) 1.2).
 */
@Service
@RequiredArgsConstructor
public class NotificationReadCommandService {

    private final NotificationRepository notificationRepository;

    /**
     * 알림 하나를 읽음으로. 이미 읽었으면 그대로 둔다(PATCH 멱등).
     *
     * @throws BusinessException {@link ErrorCode#NOTIFICATION_NOT_FOUND} — 없거나 다른 사용자의 알림. 남의 알림 존재를 드러내지
     *                           않도록 두 경우를 가르지 않는다
     */
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findByNotifIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markRead();
    }

    /** 사용자의 읽지 않은 알림 전부를 읽음으로. 바꿀 것이 없어도 성공이다. */
    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.markAllReadByUserId(userId);
    }
}
