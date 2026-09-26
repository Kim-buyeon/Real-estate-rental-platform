package com.duri.rentalplatform.config;

import com.duri.rentalplatform.domain.notification.listener.NotificationEventSubscriber;
import com.duri.rentalplatform.domain.notification.sender.SseNotificationSender;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 발행 · 구독 — 인스턴스 사이의 알림 이벤트 팬아웃(NOTI-03). 아키텍처 설계서(알림 전달) 1.1 「공유 저장소의 발행 · 구독 채널로
 * 전 인스턴스에 브로드캐스트」.
 *
 * <p>구독은 인스턴스마다 연결 하나를 따로 쥔다. 컨테이너가 닫힐 때 구독을 푼다.
 *
 * <p><b>자동 시작을 끈다</b> — 컨테이너가 기동 단계에서 구독에 실패하면 컨텍스트 전체가 실패한다. 구독은
 * {@link RedisSubscriptionStarter} 가 기동과 떼어 맺고, 실패하면 다시 시도한다. 경위와 근거는 그 클래스에 적었다.
 */
@Configuration
public class RedisPubSubConfig {

    /**
     * 구독 재시도 간격. 컨테이너가 구독이 맺힌 뒤의 끊김에 쓰는 기본 복구 간격(5초)과 맞춘다 — 기동 때와 운영 중의 재시도 간격이 같다.
     */
    private static final Duration SUBSCRIPTION_RETRY_INTERVAL =
            Duration.ofMillis(RedisMessageListenerContainer.DEFAULT_RECOVERY_INTERVAL);

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, NotificationEventSubscriber notificationEventSubscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(notificationEventSubscriber, new ChannelTopic(SseNotificationSender.CHANNEL));
        container.setAutoStartup(false);
        return container;
    }

    @Bean
    public RedisSubscriptionStarter redisSubscriptionStarter(RedisMessageListenerContainer redisMessageListenerContainer) {
        return new RedisSubscriptionStarter(redisMessageListenerContainer, SUBSCRIPTION_RETRY_INTERVAL);
    }
}
