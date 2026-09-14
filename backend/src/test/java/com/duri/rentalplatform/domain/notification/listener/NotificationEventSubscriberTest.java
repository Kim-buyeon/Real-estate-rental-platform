package com.duri.rentalplatform.domain.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.duri.rentalplatform.domain.notification.dto.response.NotificationEventResponse;
import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.sender.SseNotificationSender;
import com.duri.rentalplatform.domain.notification.store.CapturingSseEmitter;
import com.duri.rentalplatform.domain.notification.store.SseEmitterStore;
import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link NotificationEventSubscriber} 가 채널 메시지를 이 인스턴스의 연결로 옮기는지 — 이벤트 이름 · 본문(명세 1.4) · 연결 없는
 * 경우 · 해석 실패. 보관소는 실제 객체에 전송을 가로채는 연결을 넣는다.
 */
class NotificationEventSubscriberTest {

    private static final long USER_ID = 7L;
    private static final JsonMapper JSON = new JsonMapper();

    private final SseEmitterStore store = new SseEmitterStore(Duration.ofMinutes(30));
    private final NotificationEventSubscriber subscriber = new NotificationEventSubscriber(store, JSON);

    private static Message messageOf(String body) {
        return new DefaultMessage(SseNotificationSender.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static Message messageOf(NotificationDelivery delivery) {
        return messageOf(JSON.writeValueAsString(delivery));
    }

    @Test
    @DisplayName("연결을 가진 사용자에게 이벤트 이름 = 유형, 본문 = 명세 1.4 형식으로 보낸다")
    void sendsEventNamedByTypeWithSpecBody() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        store.register(USER_ID, emitter);

        subscriber.onMessage(messageOf(new NotificationDelivery(
                9012L, USER_ID, NotificationType.RISK_CHANGE, 1024L, LocalDateTime.of(2026, 7, 29, 3, 5))), null);

        assertThat(emitter.events()).hasSize(1);
        CapturingSseEmitter.Event event = emitter.events().get(0);
        assertThat(event.text()).isEqualTo("event:RISK_CHANGE\ndata:<object>\n\n");
        assertThat(event.objects()).containsExactly(new NotificationEventResponse(
                9012L, NotificationType.RISK_CHANGE, 1024L,
                OffsetDateTime.of(2026, 7, 29, 3, 5, 0, 0, ZoneOffset.ofHours(9))));
    }

    @Test
    @DisplayName("다른 사용자의 알림은 이 사용자의 연결로 보내지 않는다")
    void ignoresDeliveryForUserWithoutConnectionHere() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        store.register(USER_ID, emitter);

        subscriber.onMessage(messageOf(new NotificationDelivery(
                1L, 99L, NotificationType.REGISTRY_CHANGE, 2L, LocalDateTime.of(2026, 9, 14, 3, 0))), null);

        assertThat(emitter.events()).isEmpty();
    }

    @Test
    @DisplayName("해석할 수 없는 메시지는 버리고 예외를 올리지 않는다")
    void dropsUnreadableMessage() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        store.register(USER_ID, emitter);

        subscriber.onMessage(messageOf("{not json"), null);

        assertThat(emitter.events()).isEmpty();
    }
}
