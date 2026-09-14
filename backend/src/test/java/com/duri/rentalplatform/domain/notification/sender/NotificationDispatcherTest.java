package com.duri.rentalplatform.domain.notification.sender;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link NotificationDispatcher} — 모든 발송자에게 넘기고, 한 건 · 한 발송자의 실패를 격리한다. */
class NotificationDispatcherTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 14, 3, 5);
    private static final NotificationDelivery FIRST =
            new NotificationDelivery(9001L, 1L, NotificationType.RISK_CHANGE, 1024L, AT);
    private static final NotificationDelivery SECOND =
            new NotificationDelivery(9002L, 2L, NotificationType.RISK_CHANGE, 1024L, AT);

    @Test
    @DisplayName("알림마다 모든 발송자에게 넘긴다")
    void sendsEveryDeliveryToEverySender() {
        NotificationSender a = mock(NotificationSender.class);
        NotificationSender b = mock(NotificationSender.class);

        new NotificationDispatcher(List.of(a, b)).dispatch(List.of(FIRST, SECOND));

        verify(a).send(FIRST);
        verify(a).send(SECOND);
        verify(b).send(FIRST);
        verify(b).send(SECOND);
    }

    @Test
    @DisplayName("한 발송자가 한 건에서 실패해도 예외를 올리지 않고 다른 발송자 · 다음 건을 이어 보낸다")
    void isolatesSenderFailure() {
        NotificationSender failing = mock(NotificationSender.class);
        NotificationSender healthy = mock(NotificationSender.class);
        doThrow(new IllegalStateException("connection closed")).when(failing).send(FIRST);

        assertThatCode(() -> new NotificationDispatcher(List.of(failing, healthy)).dispatch(List.of(FIRST, SECOND)))
                .doesNotThrowAnyException();

        verify(healthy).send(FIRST);
        verify(failing).send(SECOND);
        verify(healthy).send(SECOND);
    }
}
