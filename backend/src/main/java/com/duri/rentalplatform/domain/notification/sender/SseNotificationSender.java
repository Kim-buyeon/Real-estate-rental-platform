package com.duri.rentalplatform.domain.notification.sender;

import com.duri.rentalplatform.domain.notification.vo.NotificationDelivery;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서버 전송 이벤트 발송자(NOTI-03). 연결에 직접 쓰지 않고 Redis 채널 {@value #CHANNEL} 에 발행한다.
 *
 * <p><b>왜 발행인가</b> — 앱이 두 프로세스로 뜨므로 알림을 만든 인스턴스와 사용자가 연결된 인스턴스가 다를 수 있다. 전 인스턴스에
 * 브로드캐스트하고 각 인스턴스의 구독자({@code NotificationEventSubscriber})가 연결 보유 여부를 확인해 보낸다 — 아키텍처
 * 설계서(알림 전달) 1.1. 발행한 인스턴스 자신도 구독자이므로 같은 인스턴스의 연결도 같은 경로로 받는다.
 *
 * <p><b>전달 보장 없음</b> — Pub/Sub 은 그 순간 구독 중인 쪽에만 닿는다. 연결이 없거나 끊긴 사이의 알림은 목록 조회가 확인 수단이다
 * — 명세 1.1. 발행 실패는 예외로 올려 분배자가 기록한다.
 *
 * <p>메시지는 발송 값 그대로의 JSON 이다. 받을 사용자를 담아야 구독자가 연결을 고를 수 있다 — 클라이언트 본문에서는 뺀다.
 */
@Component
public class SseNotificationSender implements NotificationSender {

    public static final String CHANNEL = "notification:events";

    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;

    public SseNotificationSender(StringRedisTemplate stringRedisTemplate, JsonMapper jsonMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void send(NotificationDelivery delivery) {
        stringRedisTemplate.convertAndSend(CHANNEL, jsonMapper.writeValueAsString(delivery));
    }
}
