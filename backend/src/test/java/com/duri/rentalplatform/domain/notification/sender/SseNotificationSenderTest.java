package com.duri.rentalplatform.domain.notification.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.duri.rentalplatform.domain.notification.enums.NotificationType;
import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** {@link SseNotificationSender} 가 발송 값을 채널 {@value SseNotificationSender#CHANNEL} 에 구독자가 되읽을 수 있는 형태로 발행하는지. */
class SseNotificationSenderTest {

    private static final JsonMapper JSON = new JsonMapper();

    @Test
    @DisplayName("발송 값을 JSON 으로 알림 이벤트 채널에 발행하고, 되읽으면 같은 값이다")
    void publishesDeliveryAsJsonToNotificationChannel() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SseNotificationSender sender = new SseNotificationSender(redis, JSON);
        NotificationDelivery delivery = new NotificationDelivery(
                9012L, 7L, NotificationType.RISK_CHANGE, 1024L, LocalDateTime.of(2026, 7, 29, 3, 5));

        sender.send(delivery);

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(redis).convertAndSend(eq("notification:events"), message.capture());
        assertThat(message.getValue()).isInstanceOf(String.class);
        assertThat(JSON.readValue((String) message.getValue(), NotificationDelivery.class)).isEqualTo(delivery);
    }
}
