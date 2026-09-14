package com.duri.rentalplatform.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.duri.rentalplatform.common.BusinessException;
import com.duri.rentalplatform.common.ErrorCode;
import com.duri.rentalplatform.domain.notification.entity.Notification;
import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.repository.NotificationRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link NotificationReadCommandService} — 본인 알림만 · 멱등 · 전체 읽음. */
class NotificationReadCommandServiceTest {

    private static final long USER_ID = 42L;
    private static final long NOTIFICATION_ID = 9012L;

    private NotificationRepository repository;
    private NotificationReadCommandService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        service = new NotificationReadCommandService(repository);
    }

    @Test
    @DisplayName("본인의 읽지 않은 알림은 읽음이 된다")
    void marksOwnNotificationRead() {
        Notification notification = Notification.unread(USER_ID, NotificationType.RISK_CHANGE);
        when(repository.findByNotifIdAndUserId(NOTIFICATION_ID, USER_ID)).thenReturn(Optional.of(notification));

        service.markRead(USER_ID, NOTIFICATION_ID);

        assertThat(notification.isRead()).isTrue();
    }

    @Test
    @DisplayName("이미 읽은 알림을 다시 읽어도 예외 없이 읽음 그대로다")
    void alreadyReadIsIdempotent() {
        Notification notification = Notification.unread(USER_ID, NotificationType.REGISTRY_CHANGE);
        notification.markRead();
        when(repository.findByNotifIdAndUserId(NOTIFICATION_ID, USER_ID)).thenReturn(Optional.of(notification));

        service.markRead(USER_ID, NOTIFICATION_ID);

        assertThat(notification.isRead()).isTrue();
    }

    @Test
    @DisplayName("없거나 남의 알림(본인 조건 조회가 비면)은 404 NOTIFICATION_NOT_FOUND")
    void missingOrOthersIsNotFound() {
        when(repository.findByNotifIdAndUserId(NOTIFICATION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(USER_ID, NOTIFICATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("전체 읽음은 사용자 단위 벌크 변경을 부른다")
    void markAllReadDelegatesBulkUpdate() {
        service.markAllRead(USER_ID);

        verify(repository).markAllReadByUserId(USER_ID);
    }
}
