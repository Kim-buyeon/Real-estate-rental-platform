package com.duri.rentalplatform.domain.notification.listener;

import com.duri.rentalplatform.domain.notification.sender.SseNotificationSender;
import com.duri.rentalplatform.domain.notification.service.NotificationStreamService;
import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 채널 {@value SseNotificationSender#CHANNEL} 의 알림을 이 인스턴스의 연결로 잇는다(NOTI-03).
 *
 * <p>모든 인스턴스가 같은 메시지를 받는다. 받을 사용자의 연결이 이 인스턴스에 있을 때만 보내고, 없으면 아무것도 하지 않는다 — 다른
 * 인스턴스가 보낸다. 아키텍처 설계서(알림 전달) 1.1.
 *
 * <p>이벤트 이름은 알림 유형, 본문은 명세 1.4 형식이다. 해석할 수 없는 메시지는 기록하고 버린다 — 구독 스레드로 예외를 올려도 재전달이
 * 없고 다음 메시지만 늦어진다. 채널 등록은 설정(Redis 발행 · 구독)이 한다.
 */
@Slf4j
@Component
public class NotificationEventSubscriber implements MessageListener {

    private final NotificationStreamService streamService;
    private final JsonMapper jsonMapper;

    public NotificationEventSubscriber(NotificationStreamService streamService, JsonMapper jsonMapper) {
        this.streamService = streamService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        NotificationDelivery delivery = read(message);
        if (delivery == null) {
            return;
        }
        if (delivery.userId() == null || delivery.type() == null) {
            log.warn("받을 사용자나 유형이 없는 알림 이벤트를 버린다. notificationId={}", delivery.notificationId());
            return;
        }
        streamService.deliver(delivery);
    }

    /** 해석하지 못하면 기록하고 null. */
    private NotificationDelivery read(Message message) {
        try {
            return jsonMapper.readValue(message.getBody(), NotificationDelivery.class);
        } catch (JacksonException e) {
            log.warn("알림 이벤트 메시지를 해석하지 못해 버린다. channel={}",
                    new String(message.getChannel(), StandardCharsets.UTF_8), e);
            return null;
        }
    }
}
