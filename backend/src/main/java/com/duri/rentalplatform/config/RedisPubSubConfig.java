package com.duri.rentalplatform.config;

import com.duri.rentalplatform.domain.notification.listener.NotificationEventSubscriber;
import com.duri.rentalplatform.domain.notification.sender.SseNotificationSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 발행 · 구독 — 인스턴스 사이의 알림 이벤트 팬아웃(NOTI-03). 아키텍처 설계서(알림 전달) 1.1 「공유 저장소의 발행 · 구독 채널로
 * 전 인스턴스에 브로드캐스트」.
 *
 * <p>구독은 인스턴스마다 연결 하나를 따로 쥔다. 컨테이너가 수명 주기 빈이라 컨텍스트 시작 때 구독하고 닫힐 때 푼다.
 */
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, NotificationEventSubscriber notificationEventSubscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(notificationEventSubscriber, new ChannelTopic(SseNotificationSender.CHANNEL));
        return container;
    }
}
